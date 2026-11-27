[CmdletBinding()]
param(
    [ValidatePattern('^[a-zA-Z0-9_.-]+$')]
    [string]$Container = 'supportforge-postgres',
    [ValidatePattern('^[a-zA-Z_][a-zA-Z0-9_]*$')]
    [string]$Database = 'supportforge',
    [ValidatePattern('^[a-zA-Z_][a-zA-Z0-9_]*$')]
    [string]$Username = 'supportforge',
    [ValidatePattern('^[a-zA-Z_][a-zA-Z0-9_]*$')]
    [string]$Schema = 'supportforge',
    [string]$OutputDirectory = 'backups/postgres',
    [ValidateRange(0, 3650)]
    [int]$RetentionDays = 0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-Docker {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker命令失败：docker $($Arguments -join ' ')"
    }
}

$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$fileName = "supportforge-$Database-$stamp.dump"
$backupFile = Join-Path $outputRoot $fileName
$containerFile = "/tmp/supportforge-pg-$stamp.dump"

try {
    Invoke-Docker -Arguments @(
        'exec', $Container, 'pg_dump', '-U', $Username, '-d', $Database,
        '-Fc', "--schema=$Schema", '--no-owner', '--no-privileges', '-f', $containerFile
    )
    Invoke-Docker -Arguments @('exec', $Container, 'pg_restore', '--list', $containerFile) | Out-Null
    Invoke-Docker -Arguments @('cp', "${Container}:${containerFile}", $backupFile)

    $hash = (Get-FileHash -LiteralPath $backupFile -Algorithm SHA256).Hash.ToLowerInvariant()
    $manifest = [ordered]@{
        format = 'postgresql-custom'
        created_at = (Get-Date).ToUniversalTime().ToString('o')
        container = $Container
        database = $Database
        schema = $Schema
        file = $fileName
        bytes = (Get-Item -LiteralPath $backupFile).Length
        sha256 = $hash
        restore_command = "scripts/operations/restore-postgres.ps1 -BackupFile '$backupFile' -TargetDatabase '<validation-or-recovery-db>'"
    }
    $manifestFile = "$backupFile.manifest.json"
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestFile -Encoding utf8

    if ($RetentionDays -gt 0) {
        $cutoff = (Get-Date).ToUniversalTime().AddDays(-$RetentionDays)
        Get-ChildItem -LiteralPath $outputRoot -File -Filter 'supportforge-*.dump' |
            Where-Object { $_.LastWriteTimeUtc -lt $cutoff } |
            ForEach-Object {
                $candidate = [IO.Path]::GetFullPath($_.FullName)
                if (-not $candidate.StartsWith($outputRoot, [StringComparison]::OrdinalIgnoreCase)) {
                    throw "拒绝清理备份根目录之外的文件：$candidate"
                }
                Remove-Item -LiteralPath $candidate
                $candidateManifest = "$candidate.manifest.json"
                if (Test-Path -LiteralPath $candidateManifest) {
                    Remove-Item -LiteralPath $candidateManifest
                }
            }
    }

    [pscustomobject]@{
        backup_file = $backupFile
        manifest_file = $manifestFile
        bytes = (Get-Item -LiteralPath $backupFile).Length
        sha256 = $hash
        verified_by_pg_restore_list = $true
    } | ConvertTo-Json
}
finally {
    & docker exec $Container rm -f $containerFile 2>$null | Out-Null
}
