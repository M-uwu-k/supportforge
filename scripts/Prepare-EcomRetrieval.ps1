[CmdletBinding()]
param(
    [string]$DataRoot = 'data/public-benchmarks/ecom',
    [string]$PythonCommand = 'python'
)

$ErrorActionPreference = 'Stop'
$datasetRevision = '687de13dc7294d6fd9be10c6945f9e8fec8166b9'
$qrelsRevision = '39c90699b034ec22ac45b3abf5b0bbb5ffd421f9'
$absoluteRoot = [System.IO.Path]::GetFullPath($DataRoot)
$raw = Join-Path $absoluteRoot 'raw'
$prepared = Join-Path $absoluteRoot 'prepared'
New-Item -ItemType Directory -Path $raw -Force | Out-Null
New-Item -ItemType Directory -Path $prepared -Force | Out-Null

$downloads = @(
    @{
        Uri = "https://huggingface.co/datasets/C-MTEB/EcomRetrieval/resolve/$datasetRevision/data/corpus-00000-of-00001-7f0e87850b5c9454.parquet"
        Path = Join-Path $raw 'corpus.parquet'
    },
    @{
        Uri = "https://huggingface.co/datasets/C-MTEB/EcomRetrieval/resolve/$datasetRevision/data/queries-00000-of-00001-46d6d2b2f0ad8826.parquet"
        Path = Join-Path $raw 'queries.parquet'
    },
    @{
        Uri = "https://huggingface.co/datasets/C-MTEB/EcomRetrieval-qrels/resolve/$qrelsRevision/data/dev-00000-of-00001-42566aaf5580f662.parquet"
        Path = Join-Path $raw 'qrels.parquet'
    }
)
foreach ($download in $downloads) {
    if (-not (Test-Path -LiteralPath $download.Path)) {
        Write-Host "Downloading $($download.Uri)"
        Invoke-WebRequest -Uri $download.Uri -OutFile $download.Path
    }
}

& $PythonCommand '.\scripts\Convert-EcomRetrieval.py' `
    --raw-dir $raw --output-dir $prepared
if ($LASTEXITCODE -ne 0) {
    throw "EcomRetrieval转换失败，Python退出码=$LASTEXITCODE"
}

$manifest = Get-Content -LiteralPath (Join-Path $prepared 'manifest.json') `
    -Raw -Encoding UTF8 | ConvertFrom-Json
Write-Host "Dataset: $($manifest.dataset_id)@$($manifest.dataset_revision)"
Write-Host "Corpus: $($manifest.corpus_count), Queries: $($manifest.query_count), Qrels: $($manifest.qrels_count)"
Write-Host "Prepared directory: $prepared"

