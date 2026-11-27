param(
    [string]$BaseUrl = 'http://localhost:7000',
    [string]$PrometheusUrl = 'http://localhost:9090',
    [string]$JaegerUrl = 'http://localhost:16686',
    [guid]$TenantId = '00000000-0000-0000-0000-000000000001'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(120)
$credentialPath = Join-Path $PSScriptRoot '../data/lan-access/tokens.json'
if (-not (Test-Path -LiteralPath $credentialPath)) { throw '请先运行Initialize-LanAccess.ps1生成本机访问凭证' }
$credentials = @([IO.File]::ReadAllText([IO.Path]::GetFullPath($credentialPath)) | ConvertFrom-Json)
$admin = @($credentials | Where-Object { $_.name -eq 'admin' })[0]
$agent = @($credentials | Where-Object { $_.name -eq 'agent' })[0]
$client.DefaultRequestHeaders.Add('Authorization', 'Bearer ' + $admin.token)
$runId = [guid]::NewGuid().ToString('N')
$actorId = $agent.actor_id
$client.DefaultRequestHeaders.Add('X-Tenant-Id', $TenantId.ToString())
$client.DefaultRequestHeaders.Add('X-Actor-Id', $actorId)
$client.DefaultRequestHeaders.Add('X-Roles', 'ADMIN')
$userId = 'wiring-check-' + $runId
$runStarted = [DateTime]::UtcNow
$question = '演示批次' + $runId + '：企业接线验证退款多久到账？'

function Invoke-CheckJson([string]$Method, [string]$Url, $Body = $null, [switch]$AllowNotFound) {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), $Url)
    try {
        if ($null -ne $Body) {
            $json = $Body | ConvertTo-Json -Depth 20 -Compress
            $request.Content = [System.Net.Http.StringContent]::new($json, [Text.Encoding]::UTF8, 'application/json')
        }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        try {
            if (-not $response.IsSuccessStatusCode) {
                if ($AllowNotFound -and [int]$response.StatusCode -eq 404) { return $null }
                throw ('HTTP check failed: ' + [int]$response.StatusCode + ' ' + ([uri]$Url).AbsolutePath)
            }
            $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if ($text) { return $text | ConvertFrom-Json }
        } finally { $response.Dispose() }
    } finally { $request.Dispose() }
}

function Invoke-CheckSql([string]$Sql) {
    $rows = @($Sql | & docker exec -i supportforge-postgres sh -c 'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At' 2>$null)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect PostgreSQL pipeline results' }
    return ($rows -join '').Trim()
}

try {
    $health = Invoke-CheckJson 'GET' ($BaseUrl + '/actuator/health/readiness')
    if ($health.status -ne 'UP') { throw 'Enterprise backend is not ready' }

    $content = "# 企业接线演示知识 $runId`n`n演示批次$runId 的企业接线验证退款多久到账：本机演示场景中，退款经人工审核通过后预计7个工作日到账。请提供订单号核验，不得声称退款已经完成。这是演示知识，不代表真实公司政策。"
    $multipart = [System.Net.Http.MultipartFormDataContent]::new()
    $file = [System.Net.Http.ByteArrayContent]::new([Text.Encoding]::UTF8.GetBytes($content))
    $file.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('text/markdown')
    $multipart.Add($file, 'file', ('enterprise-wiring-' + $runId + '.md'))
    $multipart.Add([System.Net.Http.StringContent]::new('BILLING'), 'category')
    try {
        $response = $client.PostAsync($BaseUrl + '/knowledge/upload', $multipart).GetAwaiter().GetResult()
        try {
            if (-not $response.IsSuccessStatusCode) { throw ('Knowledge upload failed: HTTP ' + [int]$response.StatusCode) }
            $import = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
        } finally { $response.Dispose() }
    } finally { $multipart.Dispose() }
    if ($import.imported -ne 1) { throw 'Knowledge import did not succeed' }
    $documentId = [string]$import.documents[0].id
    $escapedId = $documentId.Replace("'", "''")

    $indexed = 0
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt 120) {
        $indexed = [int](Invoke-CheckSql "SELECT count(*) FROM supportforge.knowledge_chunks c JOIN supportforge.knowledge_documents d ON d.id=c.document_id AND d.tenant_id=c.tenant_id WHERE d.tenant_id='$TenantId' AND d.external_key='$escapedId' AND c.index_status='INDEXED';")
        if ($indexed -gt 0) { break }
        Start-Sleep -Seconds 1
    }
    if ($indexed -eq 0) { throw 'Kafka/Embedding/Qdrant indexing did not complete within 120 seconds' }
    $rawObjects = [int](Invoke-CheckSql "SELECT count(*) FROM supportforge.knowledge_document_versions v JOIN supportforge.knowledge_documents d ON d.id=v.document_id AND d.tenant_id=v.tenant_id WHERE d.tenant_id='$TenantId' AND d.external_key='$escapedId' AND v.object_key IS NOT NULL;")
    if ($rawObjects -eq 0) { throw 'S3 raw object was not linked to the knowledge version' }

    $null = Invoke-CheckJson 'POST' ($BaseUrl + '/memory/users/' + $userId + '/facts/response_style') @{
        value = '请简洁回答'
        source = 'USER_STATEMENT'
        source_reference = 'enterprise-wiring-check'
        confidence = 1.0
    }
    $null = Invoke-CheckJson 'POST' ($BaseUrl + '/access/acl') @{
        resource_type = 'knowledge_document'
        resource_id = $documentId
        principal_id = $actorId
        permission = 'READ'
    }
    $client.DefaultRequestHeaders.Remove('X-Roles') | Out-Null
    $client.DefaultRequestHeaders.Add('X-Roles', 'AGENT')
    $client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $agent.token)

    $chat = Invoke-CheckJson 'POST' ($BaseUrl + '/chat') @{
        message = $question
        user_id = $userId
        conversation_id = 'wiring-' + $runId
    }
    if (-not $chat.grounded -or $chat.grounding_status -ne 'grounded' -or $chat.evidence.Count -lt 1) {
        throw ('Chat did not pass enterprise evidence checks: ' + $chat.grounding_status)
    }
    if ($documentId -notin @($chat.evidence | ForEach-Object { $_.external_document_id })) {
        throw 'Chat did not cite the just-indexed knowledge'
    }
    $null = Invoke-CheckJson 'POST' ($BaseUrl + '/feedback') @{
        response_id = $chat.response_id
        conversation_id = $chat.conversation_id
        rating = 'positive'
        comment = 'Enterprise wiring verification'
    }

    $messages = @(Invoke-CheckJson 'GET' ($BaseUrl + '/conversations/' + $chat.conversation_id + '/messages?user_id=' + $userId))
    if ($messages.Count -ne 2) { throw 'Conversation memory did not retain the actual question and answer' }
    $memoryRows = [int](Invoke-CheckSql "SELECT count(*) FROM supportforge.conversation_messages m JOIN supportforge.conversations c ON c.id=m.conversation_id AND c.tenant_id=m.tenant_id WHERE c.tenant_id='$TenantId' AND c.external_conversation_id='$($chat.conversation_id)';")
    $feedbackRows = [int](Invoke-CheckSql "SELECT count(*) FROM supportforge.answer_feedback f JOIN supportforge.answer_records a ON a.id=f.answer_record_id AND a.tenant_id=f.tenant_id WHERE a.tenant_id='$TenantId' AND a.response_id='$($chat.response_id)';")
    if ($memoryRows -ne 2 -or $feedbackRows -ne 1) { throw 'Conversation or feedback PostgreSQL persistence was not observed' }

    $client.DefaultRequestHeaders.Remove('X-Roles') | Out-Null
    $client.DefaultRequestHeaders.Add('X-Roles', 'ADMIN')
    $client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $admin.token)
    $learningMessage = 'enterprise-wiring-sample-' + $runId
    $learned = Invoke-CheckJson 'POST' ($BaseUrl + '/intents/learn') @{
        message = $learningMessage
        correct_intent = 'query'
    }
    $learningRows = [int](Invoke-CheckSql "SELECT count(*) FROM supportforge.intent_learning_samples WHERE tenant_id='$TenantId' AND normalized_message='$learningMessage' AND correct_intent='query' AND enabled=TRUE;")
    if (-not $learned.learned -or $learningRows -ne 1) { throw 'Intent learning did not persist in PostgreSQL' }

    $evaluation = Invoke-CheckJson 'POST' ($BaseUrl + '/eval/run') @{
        intent_cases = @()
        dialog_cases = @(@{
            question = $question
            user_id = $userId
            conversation_id = 'wiring-eval-' + $runId
        })
    }
    if ($evaluation.total -ne 1 -or $evaluation.results[0].metadata.judge_failed) { throw 'Actual model quality evaluation did not complete' }
    $evaluationRows = [int](Invoke-CheckSql "SELECT count(*) FROM supportforge.evaluation_reports WHERE tenant_id='$TenantId' AND EXISTS (SELECT 1 FROM jsonb_array_elements(item_results) item WHERE item->'metadata'->>'conversation_id'='wiring-eval-$runId');")
    if ($evaluationRows -ne 1) { throw 'Evaluation report did not persist in PostgreSQL' }

    $metrics = $client.GetStringAsync($BaseUrl + '/actuator/prometheus').GetAwaiter().GetResult()
    if ($metrics -notmatch 'supportforge_enterprise_chat_requests') { throw 'Enterprise chat metrics are missing' }
    $targets = Invoke-CheckJson 'GET' ($PrometheusUrl + '/api/v1/targets')
    $target = @($targets.data.activeTargets | Where-Object { $_.labels.job -eq 'supportforge' })
    if ($target.Count -eq 0 -or $target[0].health -ne 'up') { throw 'Prometheus is not actually scraping the backend' }

    $valkeyKeys = @(& docker exec supportforge-valkey valkey-cli --scan --pattern ('*user-facts:' + $userId))
    if ($LASTEXITCODE -ne 0 -or $valkeyKeys.Count -eq 0) { throw 'Chat did not populate the distributed user-fact cache' }

    $spans = @()
    $watch.Restart()
    while ($watch.Elapsed.TotalSeconds -lt 40) {
        $from = [uri]::EscapeDataString($runStarted.ToString('o'))
        $to = [uri]::EscapeDataString([DateTime]::UtcNow.AddSeconds(1).ToString('o'))
        $traces = Invoke-CheckJson 'GET' ($JaegerUrl + '/api/v3/traces?query.service_name=supportforge&query.operation_name=supportforge.chat.enterprise&query.search_depth=10&query.start_time_min=' + $from + '&query.start_time_max=' + $to) -AllowNotFound
        $spans = @($traces.result.resourceSpans | ForEach-Object { $_.scopeSpans } | ForEach-Object { $_.spans } | Where-Object { $_.name -eq 'supportforge.chat.enterprise' })
        if ($spans.Count -gt 0) { break }
        Start-Sleep -Seconds 1
    }
    if ($spans.Count -eq 0) { throw 'No enterprise chat trace reached Jaeger through OTLP Collector' }

    [pscustomobject]@{
        readiness = $health.status
        document_id = $documentId
        s3_objects_linked = $rawObjects
        kafka_indexed_chunks = $indexed
        chat_grounding = $chat.grounding_status
        actual_citations = $chat.evidence.Count
        retrieval_trace_id = $chat.retrieval_trace_id
        postgres_memory_messages = $memoryRows
        postgres_feedback_records = $feedbackRows
        postgres_learning_samples = $learningRows
        postgres_evaluation_reports = $evaluationRows
        judge_completed = $true
        resource_acl_applied = $true
        valkey_cache_entries = $valkeyKeys.Count
        prometheus_backend_target = $target[0].health
        jaeger_enterprise_spans = $spans.Count
    } | ConvertTo-Json -Depth 5
} finally { $client.Dispose() }
