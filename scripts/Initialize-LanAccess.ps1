param([guid]$TenantId = '00000000-0000-0000-0000-000000000001')
$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$directory = Join-Path $project 'data/lan-access'
$tokenPath = Join-Path $directory 'tokens.json'
$envPath = Join-Path $project '.env'
if (-not (Test-Path -LiteralPath $envPath)) { throw '请先从.env.example创建.env并填写部署配置' }
$envText = [IO.File]::ReadAllText($envPath)
if (Test-Path -LiteralPath $tokenPath) {
    $entries = @([IO.File]::ReadAllText($tokenPath) | ConvertFrom-Json)
} else {
    if ($envText -match '(?m)^SUPPORTFORGE_AUTH_CREDENTIALS_JSON=') { throw '已有凭证摘要，请先恢复data/lan-access/tokens.json，避免覆盖原凭证。' }
    [IO.Directory]::CreateDirectory($directory) | Out-Null
    $entries = @()
    foreach ($role in @('ADMIN', 'AGENT', 'AUDITOR', 'MONITOR')) {
        $random = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $bytes = New-Object byte[] 32; $random.GetBytes($bytes) } finally { $random.Dispose() }
        $token = 'sf_' + $role.ToLowerInvariant() + '_' + ([BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant())
        $entries += [pscustomobject]@{name=$role.ToLowerInvariant();token=$token;tenant_id=$TenantId.ToString()
            actor_id=$(if($role -eq 'MONITOR'){'prometheus'}else{'lan-'+$role.ToLowerInvariant()})
            roles=@($(if($role -eq 'MONITOR'){'AUDITOR'}else{$role}));metrics_only=($role -eq 'MONITOR')}
    }
    [IO.File]::WriteAllText($tokenPath, ($entries | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
}
$credentials = @($entries | ForEach-Object {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $hash = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($_.token))).Replace('-', '').ToLowerInvariant() } finally { $sha.Dispose() }
    @{token_sha256=$hash;tenant_id=$_.tenant_id;actor_id=$_.actor_id;roles=@($_.roles);metrics_only=[bool]$_.metrics_only}
})
$line = "SUPPORTFORGE_AUTH_CREDENTIALS_JSON='" + ($credentials | ConvertTo-Json -Depth 5 -Compress) + "'"
if($envText -match '(?m)^SUPPORTFORGE_AUTH_CREDENTIALS_JSON=') {
    $envText=[regex]::Replace($envText,'(?m)^SUPPORTFORGE_AUTH_CREDENTIALS_JSON=.*$',[System.Text.RegularExpressions.MatchEvaluator]{param($match) $line})
} else { $envText += "`r`n"+$line+"`r`n" }
[IO.File]::WriteAllText($envPath,$envText,[Text.UTF8Encoding]::new($false))
$monitor=@($entries | Where-Object {$_.name -eq 'monitor'})
if($monitor.Count -ne 1){throw '缺少独立监控凭证'}
[IO.File]::WriteAllText((Join-Path $directory 'prometheus.token'),$monitor[0].token,[Text.UTF8Encoding]::new($false))
Write-Output ('访问凭证已就绪：'+$tokenPath+'。令牌未打印到终端；重复运行保留现有令牌。')
