[CmdletBinding()]
param([switch]$BuildImage)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-Checked {
    param([string]$Label, [scriptblock]$Action)
    Write-Host "[preflight] $Label"
    & $Action
    if ($LASTEXITCODE -ne 0) { throw "$Label 失败，exit=$LASTEXITCODE" }
}

Invoke-Checked 'Maven verify（单元/集成/质量门禁）' { mvn --batch-mode --no-transfer-progress verify }
Invoke-Checked 'Compose合并配置校验' {
    docker compose -f docker-compose.yml -f docker-compose.enterprise.yml config --quiet
}
Invoke-Checked 'Kubernetes Kustomize渲染' {
    kubectl kustomize deploy/kubernetes | Out-Null
}
if ($BuildImage) {
    $stamp = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    Invoke-Checked 'production镜像构建' {
        docker build --target production --build-arg VCS_REF=local-preflight `
            --build-arg BUILD_DATE=$stamp -t supportforge:preflight .
    }
}

Write-Host '[preflight] ALL CHECKS PASSED'
