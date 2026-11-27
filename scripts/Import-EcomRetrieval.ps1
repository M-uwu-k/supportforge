[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:7000',
    [string]$DataRoot = 'data/public-benchmarks/ecom/prepared',
    [int]$BatchSize = 200,
    [string]$BearerToken = '',
    [Guid]$TenantId = [Guid]'00000000-0000-0000-0000-000000000001',
    [string]$ActorId = 'public-benchmark-importer',
    [string]$Roles = 'ADMIN',
    [int]$MaximumPassages = 0,
    [int]$MaxAttempts = 5,
    [int]$InitialRetryDelaySeconds = 2,
    [switch]$Restart
)

$ErrorActionPreference = 'Stop'
if ($MaximumPassages -lt 0) {
    throw 'MaximumPassages must be zero (all remaining passages) or a positive number'
}
if ($MaxAttempts -lt 1 -or $InitialRetryDelaySeconds -lt 1) {
    throw 'MaxAttempts and InitialRetryDelaySeconds must be positive numbers'
}
if ($BatchSize -lt 1 -or $BatchSize -gt 256) {
    throw 'BatchSize必须在1到256之间'
}
$absoluteRoot = [System.IO.Path]::GetFullPath($DataRoot)
$manifestPath = Join-Path $absoluteRoot 'manifest.json'
$corpusPath = Join-Path $absoluteRoot 'corpus.jsonl'
$checkpointPath = Join-Path $absoluteRoot 'import-checkpoint.json'
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json

$headers = @{}
if ([string]::IsNullOrWhiteSpace($BearerToken)) {
    $headers['X-Tenant-Id'] = $TenantId.ToString()
    $headers['X-Actor-Id'] = $ActorId
    $headers['X-Roles'] = $Roles
} else {
    $headers['Authorization'] = 'Bearer ' + $BearerToken
}
$headers['X-Request-Id'] = 'ecom-import-' + [Guid]::NewGuid().ToString('N')
$endpoint = $BaseUrl.TrimEnd('/') + '/quality/public-retrieval-benchmarks'
Invoke-RestMethod -Method Post -Uri ($endpoint + '/ensure') -Headers $headers `
    -TimeoutSec 300 | Out-Null

$nextLine = 0
if ($Restart -and (Test-Path -LiteralPath $checkpointPath)) {
    Remove-Item -LiteralPath $checkpointPath
}
if (Test-Path -LiteralPath $checkpointPath) {
    $checkpoint = Get-Content -LiteralPath $checkpointPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($checkpoint.dataset_revision -ne $manifest.dataset_revision) {
        throw '断点文件的数据集revision与manifest不一致'
    }
    $nextLine = [int]$checkpoint.next_line
}
$targetLine = [int]$manifest.corpus_count
if ($MaximumPassages -gt 0) {
    $targetLine = [Math]::Min($targetLine, $nextLine + $MaximumPassages)
}

$utf8 = [System.Text.UTF8Encoding]::new($false)
$reader = [System.IO.StreamReader]::new($corpusPath, $utf8, $true)
try {
    for ($index = 0; $index -lt $nextLine; $index++) {
        if ($null -eq $reader.ReadLine()) { throw '断点超过corpus行数' }
    }
    while ($nextLine -lt $targetLine) {
        $passages = [System.Collections.Generic.List[object]]::new()
        $remaining = $targetLine - $nextLine
        $currentBatchSize = [Math]::Min($BatchSize, $remaining)
        while ($passages.Count -lt $currentBatchSize) {
            $line = $reader.ReadLine()
            if ($null -eq $line) { break }
            $passages.Add(($line | ConvertFrom-Json))
        }
        if ($passages.Count -eq 0) { break }
        $body = @{
            dataset_id = $manifest.dataset_id
            dataset_revision = $manifest.dataset_revision
            passages = $passages
        } | ConvertTo-Json -Depth 8 -Compress
        $result = $null
        for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
            try {
                $result = Invoke-RestMethod -Method Post -Uri ($endpoint + '/corpus/import') `
                    -Headers $headers -ContentType 'application/json; charset=utf-8' `
                    -Body $body -TimeoutSec 3600
                break
            } catch {
                if ($attempt -eq $MaxAttempts) { throw }
                $delay = [Math]::Min(
                    30,
                    $InitialRetryDelaySeconds * [Math]::Pow(2, $attempt - 1)
                )
                Write-Warning "Import batch at line $nextLine failed on attempt $attempt/$MaxAttempts; retrying in $delay seconds"
                Start-Sleep -Seconds $delay
            }
        }
        if ([int]$result.upserted -ne $passages.Count) {
            throw "导入数量不一致：sent=$($passages.Count), upserted=$($result.upserted)"
        }
        $nextLine += $passages.Count
        @{
            dataset_revision = $manifest.dataset_revision
            next_line = $nextLine
            indexed_points = $result.indexed_points
            updated_at = [DateTimeOffset]::UtcNow.ToString('O')
        } | ConvertTo-Json | Set-Content -LiteralPath $checkpointPath -Encoding UTF8
        Write-Host "Imported $nextLine/$($manifest.corpus_count), Qdrant points=$($result.indexed_points)"
    }
} finally {
    $reader.Dispose()
}

$status = Invoke-RestMethod -Method Get -Uri ($endpoint + '/status') `
    -Headers $headers -TimeoutSec 300
if ($nextLine -eq [int]$manifest.corpus_count) {
    if ([long]$status.indexed_points -ne [long]$manifest.corpus_count) {
        throw "Full import validation failed: Qdrant=$($status.indexed_points), manifest=$($manifest.corpus_count)"
    }
    Write-Host "EcomRetrieval import completed: $($status.indexed_points) points"
} else {
    if ([long]$status.indexed_points -lt [long]$nextLine) {
        throw "Partial import validation failed: Qdrant=$($status.indexed_points), checkpoint=$nextLine"
    }
    Write-Host "EcomRetrieval partial import completed: checkpoint=$nextLine/$($manifest.corpus_count), Qdrant points=$($status.indexed_points)"
}

