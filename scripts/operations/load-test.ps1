[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:7000',
    [ValidateRange(1, 100000)]
    [int]$Requests = 120,
    [ValidateRange(1, 256)]
    [int]$Concurrency = 12,
    [ValidatePattern('^[0-9a-fA-F-]{36}$')]
    [string]$TenantId = '00000000-0000-0000-0000-000000000001',
    [string]$ActorId = 'capacity-probe',
    [string]$Roles = 'ADMIN,AGENT',
    [ValidateRange(0.0, 1.0)]
    [double]$MinimumSuccessRate = 0.99,
    [ValidateRange(1, 600000)]
    [int]$MaximumP95Milliseconds = 2000,
    [string]$OutputPath = 'target/capacity-result.json'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw '容量脚本需要PowerShell 7或更高版本，以使用受控并行执行'
}

$health = Invoke-WebRequest -Uri "$BaseUrl/actuator/health/readiness" -TimeoutSec 10 -SkipHttpErrorCheck
if ($health.StatusCode -ne 200) { throw "readiness预检失败：HTTP $($health.StatusCode)" }

$wall = [Diagnostics.Stopwatch]::StartNew()
$results = 1..$Requests | ForEach-Object -Parallel {
    $sequence = $_
    $headers = @{
        'X-Tenant-Id' = $using:TenantId
        'X-Actor-Id' = $using:ActorId
        'X-Roles' = $using:Roles
        'X-Request-Id' = "capacity-$sequence-$([guid]::NewGuid().ToString('N'))"
    }
    $conversation = "capacity-$sequence-$([guid]::NewGuid().ToString('N'))"
    $body = @{
        user_id = "capacity-user-$($sequence % 20)"
        conversation_id = $conversation
        message = "登录失败提示401，请告诉我排查步骤。请求编号$sequence"
    } | ConvertTo-Json -Compress
    $timer = [Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-WebRequest -Method Post -Uri "$using:BaseUrl/chat" `
            -Headers $headers -ContentType 'application/json' -Body $body `
            -TimeoutSec 30 -SkipHttpErrorCheck
        $timer.Stop()
        [pscustomobject]@{
            status = [int]$response.StatusCode
            elapsed_ms = [math]::Round($timer.Elapsed.TotalMilliseconds, 2)
            error = ''
        }
    }
    catch {
        $timer.Stop()
        [pscustomobject]@{
            status = 0
            elapsed_ms = [math]::Round($timer.Elapsed.TotalMilliseconds, 2)
            error = $_.Exception.Message
        }
    }
} -ThrottleLimit $Concurrency
$wall.Stop()

$latencies = @($results | ForEach-Object { [double]$_.elapsed_ms } | Sort-Object)
function Get-Percentile([double[]]$Values, [double]$Percentile) {
    if ($Values.Count -eq 0) { return 0.0 }
    $index = [math]::Max(0, [math]::Ceiling($Percentile * $Values.Count) - 1)
    return [math]::Round($Values[$index], 2)
}

$successes = @($results | Where-Object { $_.status -ge 200 -and $_.status -lt 300 }).Count
$successRate = [math]::Round($successes / [double]$Requests, 6)
$p95 = Get-Percentile $latencies 0.95
$summary = [ordered]@{
    generated_at = (Get-Date).ToUniversalTime().ToString('o')
    base_url = $BaseUrl
    requests = $Requests
    concurrency = $Concurrency
    successes = $successes
    failures = $Requests - $successes
    success_rate = $successRate
    wall_seconds = [math]::Round($wall.Elapsed.TotalSeconds, 3)
    throughput_rps = [math]::Round($Requests / $wall.Elapsed.TotalSeconds, 2)
    latency_ms = [ordered]@{
        minimum = [math]::Round($latencies[0], 2)
        p50 = Get-Percentile $latencies 0.50
        p95 = $p95
        p99 = Get-Percentile $latencies 0.99
        maximum = [math]::Round($latencies[-1], 2)
    }
    status_counts = @($results | Group-Object status | ForEach-Object {
        [ordered]@{ status = [int]$_.Name; count = $_.Count }
    })
    gate = [ordered]@{
        minimum_success_rate = $MinimumSuccessRate
        maximum_p95_ms = $MaximumP95Milliseconds
        passed = ($successRate -ge $MinimumSuccessRate -and $p95 -le $MaximumP95Milliseconds)
    }
}

$resolvedOutput = [IO.Path]::GetFullPath($OutputPath)
$parent = Split-Path -Parent $resolvedOutput
New-Item -ItemType Directory -Path $parent -Force | Out-Null
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resolvedOutput -Encoding utf8
$summary | ConvertTo-Json -Depth 8
if (-not $summary.gate.passed) {
    throw "容量门禁失败：success_rate=$successRate, p95=${p95}ms"
}
