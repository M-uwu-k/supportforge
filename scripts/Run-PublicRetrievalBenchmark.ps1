[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:7000',
    [string]$DataRoot = 'data/public-benchmarks/ecom/prepared',
    [string]$OutputPath = '',
    [int]$K = 10,
    [int]$CandidateK = 100,
    [string]$BearerToken = '',
    [Guid]$TenantId = [Guid]'00000000-0000-0000-0000-000000000001',
    [string]$ActorId = 'public-benchmark-runner',
    [string]$Roles = 'AUDITOR'
)

$ErrorActionPreference = 'Stop'
$absoluteRoot = [System.IO.Path]::GetFullPath($DataRoot)
$manifest = Get-Content -LiteralPath (Join-Path $absoluteRoot 'manifest.json') `
    -Raw -Encoding UTF8 | ConvertFrom-Json
$queriesPath = Join-Path $absoluteRoot 'queries.jsonl'
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $OutputPath = "data/benchmark-reports/ecom-public-$timestamp.json"
}
$absoluteOutput = [System.IO.Path]::GetFullPath($OutputPath)
$outputDirectory = Split-Path -Parent $absoluteOutput
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null

$headers = @{}
if ([string]::IsNullOrWhiteSpace($BearerToken)) {
    $headers['X-Tenant-Id'] = $TenantId.ToString()
    $headers['X-Actor-Id'] = $ActorId
    $headers['X-Roles'] = $Roles
} else {
    $headers['Authorization'] = 'Bearer ' + $BearerToken
}
$headers['X-Request-Id'] = 'ecom-benchmark-' + [Guid]::NewGuid().ToString('N')

$queries = [System.Collections.Generic.List[object]]::new()
$reader = [System.IO.StreamReader]::new($queriesPath, [System.Text.UTF8Encoding]::new($false), $true)
try {
    while ($null -ne ($line = $reader.ReadLine())) {
        if (-not [string]::IsNullOrWhiteSpace($line)) {
            $queries.Add(($line | ConvertFrom-Json))
        }
    }
} finally {
    $reader.Dispose()
}
$request = @{
    dataset_id = $manifest.dataset_id
    dataset_revision = $manifest.dataset_revision
    k = $K
    candidate_k = $CandidateK
    require_real_providers = $true
    queries = $queries
}
$body = $request | ConvertTo-Json -Depth 15 -Compress
$endpoint = $BaseUrl.TrimEnd('/') + '/quality/public-retrieval-benchmarks/run'
$report = Invoke-RestMethod -Method Post -Uri $endpoint -Headers $headers `
    -ContentType 'application/json; charset=utf-8' -Body $body -TimeoutSec 7200
$report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $absoluteOutput -Encoding UTF8

$rows = foreach ($variant in @($report.variants)) {
    $m = $variant.metrics
    "| $($variant.variant) | $([math]::Round([double]$m.ndcg_at_k, 4)) | " +
    "$([math]::Round([double]$m.map_at_k, 4)) | $([math]::Round([double]$m.mrr_at_k, 4)) | " +
    "$([math]::Round([double]$m.recall_at_k * 100, 2))% | " +
    "$([math]::Round([double]$m.candidate_recall * 100, 2))% | " +
    "$($m.candidate_misses) | $($m.ranking_misses) | " +
    "$([math]::Round([double]$m.p95_latency_ms, 2)) ms |"
}
$markdownPath = [System.IO.Path]::ChangeExtension($absoluteOutput, '.md')
$markdown = @"
# SupportForge C-MTEB EcomRetrieval Benchmark

- Dataset: ``$($report.dataset_id)``
- Revision: ``$($report.dataset_revision)``
- Corpus points: $($report.indexed_points)
- Queries: $($report.variants[0].metrics.queries)
- Candidate K: $($report.candidate_k)
- Final K: $($report.k)
- Collection: ``$($report.collection)``
- Real providers: $($report.environment.real_providers)

| Variant | nDCG@$K | MAP@$K | MRR@$K | Recall@$K | Candidate Recall@$CandidateK | Candidate Miss | Ranking Miss | P95 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
$($rows -join "`n")

Dense-only is the closest variant to the public embedding benchmark protocol.
BM25/Hybrid/Reranker rows are SupportForge system results and must not be presented as MTEB leaderboard submissions.
"@
$markdown | Set-Content -LiteralPath $markdownPath -Encoding UTF8
Write-Host 'Public benchmark completed.'
Write-Host "JSON report: $absoluteOutput"
Write-Host "Markdown report: $markdownPath"
$rows | ForEach-Object { Write-Host $_ }

