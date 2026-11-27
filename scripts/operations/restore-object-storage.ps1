[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BackupDirectory,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$')]
    [string]$TargetBucket,
    [ValidatePattern('^[a-zA-Z0-9_.-]+$')]
    [string]$Container = 'supportforge-silo',
    [Parameter(Mandatory = $true)]
    [string]$AccessKey,
    [Parameter(Mandatory = $true)]
    [string]$SecretKey,
    [switch]$RemoveObjectsAbsentFromBackup
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-Docker {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker命令失败：docker $($Arguments -join ' ')" }
}

$root = [IO.Path]::GetFullPath($BackupDirectory)
$objects = Join-Path $root 'objects'
$manifestFile = Join-Path $root 'manifest.json'
if (-not (Test-Path -LiteralPath $objects -PathType Container) -or
    -not (Test-Path -LiteralPath $manifestFile -PathType Leaf)) {
    throw '备份目录必须同时包含objects目录和manifest.json'
}

$manifest = Get-Content -LiteralPath $manifestFile -Raw | ConvertFrom-Json
foreach ($entry in @($manifest.objects)) {
    $file = Join-Path $objects ([string]$entry.key).Replace('/', [IO.Path]::DirectorySeparatorChar)
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "备份对象缺失：$($entry.key)" }
    $hash = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hash -ne [string]$entry.sha256) { throw "对象校验和不一致：$($entry.key)" }
}

$token = [guid]::NewGuid().ToString('N')
$containerRoot = "/tmp/supportforge-object-restore-$token"
try {
    Invoke-Docker -Arguments @('exec', $Container, 'mkdir', '-p', "$containerRoot/objects")
    Invoke-Docker -Arguments @('cp', "$objects/.", "${Container}:$containerRoot/objects")
    Invoke-Docker -Arguments @(
        'exec', $Container, 'mc', 'alias', 'set', 'supportforge-restore',
        'http://127.0.0.1:9000', $AccessKey, $SecretKey
    )
    Invoke-Docker -Arguments @(
        'exec', $Container, 'mc', 'mb', '--ignore-existing',
        "supportforge-restore/$TargetBucket"
    )
    $mirrorArgs = @('exec', $Container, 'mc', 'mirror', '--overwrite')
    if ($RemoveObjectsAbsentFromBackup) { $mirrorArgs += '--remove' }
    $mirrorArgs += @("$containerRoot/objects", "supportforge-restore/$TargetBucket")
    Invoke-Docker -Arguments $mirrorArgs

    $listed = @(& docker exec $Container mc ls --recursive --json "supportforge-restore/$TargetBucket")
    if ($LASTEXITCODE -ne 0) { throw '恢复后对象枚举失败' }
    [pscustomobject]@{
        restored_bucket = $TargetBucket
        expected_objects = @($manifest.objects).Count
        listed_objects = @($listed | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count
        checksum_verified = $true
        restore_completed = $true
    } | ConvertTo-Json
}
finally {
    if ($containerRoot -match '^/tmp/supportforge-object-restore-[a-f0-9]{32}$') {
        & docker exec $Container rm -rf $containerRoot 2>$null | Out-Null
    }
}
