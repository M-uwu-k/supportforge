[CmdletBinding()]
param(
    [ValidatePattern('^[a-zA-Z0-9_.-]+$')]
    [string]$Container = 'supportforge-silo',
    [ValidatePattern('^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$')]
    [string]$Bucket = 'supportforge-knowledge',
    [Parameter(Mandatory = $true)]
    [string]$AccessKey,
    [Parameter(Mandatory = $true)]
    [string]$SecretKey,
    [string]$OutputDirectory = 'backups/object-storage'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-Docker {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker命令失败：docker $($Arguments -join ' ')" }
}

$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
$backupRoot = Join-Path $outputRoot "$Bucket-$stamp"
New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
$containerRoot = "/tmp/supportforge-object-backup-$stamp"

try {
    Invoke-Docker -Arguments @(
        'exec', $Container, 'mc', 'alias', 'set', 'supportforge-backup',
        'http://127.0.0.1:9000', $AccessKey, $SecretKey
    )
    Invoke-Docker -Arguments @('exec', $Container, 'mkdir', '-p', "$containerRoot/objects")
    Invoke-Docker -Arguments @(
        'exec', $Container, 'mc', 'mirror', '--preserve',
        "supportforge-backup/$Bucket", "$containerRoot/objects"
    )
    Invoke-Docker -Arguments @('cp', "${Container}:$containerRoot/objects", $backupRoot)

    $objectsRoot = Join-Path $backupRoot 'objects'
    $files = @(
        Get-ChildItem -LiteralPath $objectsRoot -Recurse -File | ForEach-Object {
            [ordered]@{
                key = [IO.Path]::GetRelativePath($objectsRoot, $_.FullName).Replace('\', '/')
                bytes = $_.Length
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            }
        }
    )
    $manifest = [ordered]@{
        format = 's3-object-mirror'
        created_at = (Get-Date).ToUniversalTime().ToString('o')
        bucket = $Bucket
        object_count = $files.Count
        objects = $files
    }
    $manifestFile = Join-Path $backupRoot 'manifest.json'
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestFile -Encoding utf8
    [pscustomobject]@{
        backup_directory = $backupRoot
        manifest_file = $manifestFile
        object_count = $files.Count
        backup_completed = $true
    } | ConvertTo-Json
}
finally {
    if ($containerRoot -match '^/tmp/supportforge-object-backup-[0-9]{8}T[0-9]{6}Z$') {
        & docker exec $Container rm -rf $containerRoot 2>$null | Out-Null
    }
}
