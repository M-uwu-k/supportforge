[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BackupFile,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-zA-Z_][a-zA-Z0-9_]*$')]
    [string]$TargetDatabase,
    [ValidatePattern('^[a-zA-Z0-9_.-]+$')]
    [string]$Container = 'supportforge-postgres',
    [ValidatePattern('^[a-zA-Z_][a-zA-Z0-9_]*$')]
    [string]$Username = 'supportforge',
    [switch]$DropExisting,
    [switch]$AllowProductionDatabase
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

$reserved = @('postgres', 'template0', 'template1', 'supportforge')
if (-not $AllowProductionDatabase -and $reserved -contains $TargetDatabase) {
    throw "默认拒绝恢复到保留/生产数据库 $TargetDatabase；灾难恢复时需显式使用 -AllowProductionDatabase"
}

$resolvedBackup = [IO.Path]::GetFullPath($BackupFile)
if (-not (Test-Path -LiteralPath $resolvedBackup -PathType Leaf)) {
    throw "备份文件不存在：$resolvedBackup"
}

$manifestFile = "$resolvedBackup.manifest.json"
if (Test-Path -LiteralPath $manifestFile) {
    $manifest = Get-Content -LiteralPath $manifestFile -Raw | ConvertFrom-Json
    $actualHash = (Get-FileHash -LiteralPath $resolvedBackup -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne [string]$manifest.sha256) {
        throw "备份SHA-256与manifest不一致，拒绝恢复"
    }
}

$token = [guid]::NewGuid().ToString('N')
$containerFile = "/tmp/supportforge-restore-$token.dump"
try {
    Invoke-Docker -Arguments @('cp', $resolvedBackup, "${Container}:${containerFile}")
    Invoke-Docker -Arguments @('exec', $Container, 'pg_restore', '--list', $containerFile) | Out-Null

    $exists = & docker exec $Container psql -U $Username -d postgres -Atc `
        "SELECT 1 FROM pg_database WHERE datname='$TargetDatabase'"
    if ($LASTEXITCODE -ne 0) { throw '检查目标数据库失败' }

    if (($exists | Out-String).Trim() -eq '1') {
        if (-not $DropExisting) {
            throw "目标数据库已存在：$TargetDatabase；如确认重建请使用 -DropExisting"
        }
        Invoke-Docker -Arguments @(
            'exec', $Container, 'psql', '-U', $Username, '-d', 'postgres',
            '-v', 'ON_ERROR_STOP=1', '-c',
            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='$TargetDatabase' AND pid<>pg_backend_pid();"
        )
        Invoke-Docker -Arguments @('exec', $Container, 'dropdb', '-U', $Username, $TargetDatabase)
    }

    Invoke-Docker -Arguments @('exec', $Container, 'createdb', '-U', $Username, $TargetDatabase)
    Invoke-Docker -Arguments @(
        'exec', $Container, 'pg_restore', '-U', $Username, '-d', $TargetDatabase,
        '--no-owner', '--no-privileges', '--exit-on-error', $containerFile
    )

    $tableCount = & docker exec $Container psql -U $Username -d $TargetDatabase -Atc `
        "SELECT count(*) FROM information_schema.tables WHERE table_schema='supportforge' AND table_type='BASE TABLE'"
    if ($LASTEXITCODE -ne 0) { throw '恢复后表数量验证失败' }
    $flywayVersion = & docker exec $Container psql -U $Username -d $TargetDatabase -Atc `
        'SELECT version FROM supportforge.flyway_schema_history WHERE success=true ORDER BY installed_rank DESC LIMIT 1'
    if ($LASTEXITCODE -ne 0) { throw '恢复后Flyway版本验证失败' }

    [pscustomobject]@{
        restored_database = $TargetDatabase
        supportforge_tables = [int](($tableCount | Out-String).Trim())
        flyway_version = (($flywayVersion | Out-String).Trim())
        checksum_verified = (Test-Path -LiteralPath $manifestFile)
        restore_completed = $true
    } | ConvertTo-Json
}
finally {
    & docker exec $Container rm -f $containerFile 2>$null | Out-Null
}
