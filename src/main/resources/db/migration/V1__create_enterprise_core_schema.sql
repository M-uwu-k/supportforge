CREATE SCHEMA IF NOT EXISTS supportforge;
SET search_path TO supportforge, public;

CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    tenant_key VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_tenants_status
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED'))
);

CREATE TABLE customer_identities (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    external_user_id VARCHAR(200) NOT NULL,
    display_name VARCHAR(200),
    profile JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_customer_identities_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT uq_customer_identities_tenant_id
        UNIQUE (tenant_id, id),
    CONSTRAINT uq_customer_identities_external
        UNIQUE (tenant_id, external_user_id),
    CONSTRAINT ck_customer_identities_status
        CHECK (status IN ('ACTIVE', 'BLOCKED', 'DELETED'))
);

CREATE TABLE conversations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    external_conversation_id VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    channel VARCHAR(64) NOT NULL DEFAULT 'API',
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_message_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_conversations_tenant_id
        UNIQUE (tenant_id, id),
    CONSTRAINT uq_conversations_external
        UNIQUE (tenant_id, external_conversation_id),
    CONSTRAINT fk_conversations_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_conversations_customer
        FOREIGN KEY (tenant_id, customer_id)
        REFERENCES customer_identities(tenant_id, id),
    CONSTRAINT ck_conversations_status
        CHECK (status IN ('OPEN', 'ESCALATED', 'RESOLVED', 'CLOSED'))
);

CREATE TABLE conversation_messages (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    sequence_no BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    model_name VARCHAR(200),
    token_count INTEGER,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_conversation_messages_tenant_id
        UNIQUE (tenant_id, id),
    CONSTRAINT uq_conversation_messages_sequence
        UNIQUE (tenant_id, conversation_id, sequence_no),
    CONSTRAINT fk_conversation_messages_conversation
        FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES conversations(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_conversation_messages_sequence
        CHECK (sequence_no >= 0),
    CONSTRAINT ck_conversation_messages_role
        CHECK (role IN ('SYSTEM', 'USER', 'ASSISTANT', 'TOOL')),
    CONSTRAINT ck_conversation_messages_tokens
        CHECK (token_count IS NULL OR token_count >= 0)
);

CREATE TABLE conversation_summaries (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    version_no INTEGER NOT NULL,
    summary TEXT NOT NULL,
    source_message_count INTEGER NOT NULL DEFAULT 0,
    model_name VARCHAR(200),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_conversation_summaries_version
        UNIQUE (tenant_id, conversation_id, version_no),
    CONSTRAINT fk_conversation_summaries_conversation
        FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES conversations(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_conversation_summaries_version
        CHECK (version_no >= 1),
    CONSTRAINT ck_conversation_summaries_message_count
        CHECK (source_message_count >= 0)
);

CREATE TABLE user_facts (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    fact_key VARCHAR(160) NOT NULL,
    fact_value JSONB NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_reference VARCHAR(300),
    confidence NUMERIC(6, 5) NOT NULL DEFAULT 1.0,
    valid_from TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_facts_tenant_id
        UNIQUE (tenant_id, id),
    CONSTRAINT fk_user_facts_customer
        FOREIGN KEY (tenant_id, customer_id)
        REFERENCES customer_identities(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_user_facts_confidence
        CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT ck_user_facts_validity
        CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

CREATE UNIQUE INDEX uq_user_facts_active
    ON user_facts(tenant_id, customer_id, fact_key)
    WHERE valid_to IS NULL;

CREATE TABLE knowledge_documents (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    external_key VARCHAR(240) NOT NULL,
    title VARCHAR(500) NOT NULL,
    category VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    source_type VARCHAR(64) NOT NULL,
    source_uri TEXT,
    active_version_no INTEGER,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_knowledge_documents_tenant_id
        UNIQUE (tenant_id, id),
    CONSTRAINT uq_knowledge_documents_external
        UNIQUE (tenant_id, external_key),
    CONSTRAINT fk_knowledge_documents_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT ck_knowledge_documents_status
        CHECK (status IN ('DRAFT', 'PROCESSING', 'ACTIVE', 'ARCHIVED', 'FAILED', 'DELETED')),
    CONSTRAINT ck_knowledge_documents_active_version
        CHECK (active_version_no IS NULL OR active_version_no >= 1)
);

CREATE TABLE knowledge_document_versions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    document_id UUID NOT NULL,
    version_no INTEGER NOT NULL,
    checksum_sha256 CHAR(64) NOT NULL,
    content_type VARCHAR(160) NOT NULL,
    object_key TEXT,
    parser_version VARCHAR(100),
    ingestion_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    failure_reason TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_knowledge_document_versions_tenant_id
        UNIQUE (tenant_id, id),
    CONSTRAINT uq_knowledge_document_versions_number
        UNIQUE (tenant_id, document_id, version_no),
    CONSTRAINT fk_knowledge_document_versions_document
        FOREIGN KEY (tenant_id, document_id)
        REFERENCES knowledge_documents(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_knowledge_document_versions_number
        CHECK (version_no >= 1),
    CONSTRAINT ck_knowledge_document_versions_checksum
        CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_knowledge_document_versions_status
        CHECK (ingestion_status IN ('PENDING', 'PARSING', 'CHUNKING', 'INDEXING', 'READY', 'FAILED'))
);

CREATE TABLE knowledge_chunks (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    document_id UUID NOT NULL,
    document_version_id UUID NOT NULL,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    token_count INTEGER,
    embedding_model VARCHAR(200),
    embedding_version VARCHAR(100),
    qdrant_point_id UUID,
    index_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_knowledge_chunks_tenant_id
        UNIQUE (tenant_id, id),
    CONSTRAINT uq_knowledge_chunks_position
        UNIQUE (tenant_id, document_version_id, chunk_index),
    CONSTRAINT uq_knowledge_chunks_qdrant_point
        UNIQUE (tenant_id, qdrant_point_id),
    CONSTRAINT fk_knowledge_chunks_document
        FOREIGN KEY (tenant_id, document_id)
        REFERENCES knowledge_documents(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_chunks_version
        FOREIGN KEY (tenant_id, document_version_id)
        REFERENCES knowledge_document_versions(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_knowledge_chunks_index
        CHECK (chunk_index >= 0),
    CONSTRAINT ck_knowledge_chunks_hash
        CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_knowledge_chunks_tokens
        CHECK (token_count IS NULL OR token_count >= 0),
    CONSTRAINT ck_knowledge_chunks_status
        CHECK (index_status IN ('PENDING', 'INDEXING', 'INDEXED', 'STALE', 'FAILED'))
);

CREATE TABLE knowledge_ingestion_jobs (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    document_id UUID NOT NULL,
    document_version_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner VARCHAR(200),
    lease_until TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_knowledge_ingestion_jobs_tenant_id
        UNIQUE (tenant_id, id),
    CONSTRAINT uq_knowledge_ingestion_jobs_version
        UNIQUE (tenant_id, document_version_id),
    CONSTRAINT fk_knowledge_ingestion_jobs_document
        FOREIGN KEY (tenant_id, document_id)
        REFERENCES knowledge_documents(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_ingestion_jobs_version
        FOREIGN KEY (tenant_id, document_version_id)
        REFERENCES knowledge_document_versions(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_knowledge_ingestion_jobs_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_knowledge_ingestion_jobs_attempts
        CHECK (attempt_count >= 0)
);

CREATE TABLE answer_records (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    response_id VARCHAR(200) NOT NULL,
    request_message_id UUID,
    response_message_id UUID,
    intent VARCHAR(100),
    agent_type VARCHAR(100),
    response_text TEXT NOT NULL,
    routing_confidence NUMERIC(6, 5),
    escalated BOOLEAN NOT NULL DEFAULT FALSE,
    model_name VARCHAR(200),
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    latency_ms BIGINT,
    evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_answer_records_tenant_id
        UNIQUE (tenant_id, id),
    CONSTRAINT uq_answer_records_response
        UNIQUE (tenant_id, response_id),
    CONSTRAINT fk_answer_records_conversation
        FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES conversations(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_answer_records_request_message
        FOREIGN KEY (tenant_id, request_message_id)
        REFERENCES conversation_messages(tenant_id, id),
    CONSTRAINT fk_answer_records_response_message
        FOREIGN KEY (tenant_id, response_message_id)
        REFERENCES conversation_messages(tenant_id, id),
    CONSTRAINT ck_answer_records_confidence
        CHECK (routing_confidence IS NULL OR (routing_confidence >= 0 AND routing_confidence <= 1)),
    CONSTRAINT ck_answer_records_tokens
        CHECK ((prompt_tokens IS NULL OR prompt_tokens >= 0)
            AND (completion_tokens IS NULL OR completion_tokens >= 0)),
    CONSTRAINT ck_answer_records_latency
        CHECK (latency_ms IS NULL OR latency_ms >= 0)
);

CREATE TABLE answer_feedback (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    answer_record_id UUID NOT NULL,
    rating INTEGER NOT NULL,
    comment TEXT,
    labels JSONB NOT NULL DEFAULT '[]'::jsonb,
    submitted_by VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_answer_feedback_answer
        UNIQUE (tenant_id, answer_record_id),
    CONSTRAINT fk_answer_feedback_answer
        FOREIGN KEY (tenant_id, answer_record_id)
        REFERENCES answer_records(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_answer_feedback_rating
        CHECK (rating BETWEEN 1 AND 5)
);

CREATE TABLE evaluation_reports (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    evaluation_type VARCHAR(64) NOT NULL,
    dataset_version VARCHAR(160),
    model_name VARCHAR(200),
    status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    sample_count INTEGER NOT NULL DEFAULT 0,
    metrics JSONB NOT NULL DEFAULT '{}'::jsonb,
    item_results JSONB NOT NULL DEFAULT '[]'::jsonb,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_evaluation_reports_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT ck_evaluation_reports_status
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_evaluation_reports_sample_count
        CHECK (sample_count >= 0)
);

CREATE TABLE intent_learning_samples (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    message TEXT NOT NULL,
    normalized_message TEXT NOT NULL,
    correct_intent VARCHAR(100) NOT NULL,
    source VARCHAR(64) NOT NULL DEFAULT 'HUMAN_CORRECTION',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_intent_learning_samples_message
        UNIQUE (tenant_id, normalized_message),
    CONSTRAINT fk_intent_learning_samples_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants(id)
);

CREATE TABLE retrieval_traces (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    conversation_id UUID,
    answer_record_id UUID,
    original_query TEXT NOT NULL,
    rewritten_queries JSONB NOT NULL DEFAULT '[]'::jsonb,
    filters JSONB NOT NULL DEFAULT '{}'::jsonb,
    recalled_chunks JSONB NOT NULL DEFAULT '[]'::jsonb,
    selected_evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    embedding_model VARCHAR(200),
    reranker_model VARCHAR(200),
    retrieval_latency_ms BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_retrieval_traces_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_retrieval_traces_conversation
        FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES conversations(tenant_id, id)
        ON DELETE SET NULL (conversation_id),
    CONSTRAINT fk_retrieval_traces_answer
        FOREIGN KEY (tenant_id, answer_record_id)
        REFERENCES answer_records(tenant_id, id)
        ON DELETE SET NULL (answer_record_id),
    CONSTRAINT ck_retrieval_traces_latency
        CHECK (retrieval_latency_ms IS NULL OR retrieval_latency_ms >= 0)
);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(160) NOT NULL,
    event_version INTEGER NOT NULL DEFAULT 1,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_outbox_events_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT ck_outbox_events_version
        CHECK (event_version >= 1),
    CONSTRAINT ck_outbox_events_status
        CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED', 'DEAD')),
    CONSTRAINT ck_outbox_events_attempts
        CHECK (attempt_count >= 0)
);

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    actor_type VARCHAR(64) NOT NULL,
    actor_id VARCHAR(200),
    action VARCHAR(160) NOT NULL,
    resource_type VARCHAR(100) NOT NULL,
    resource_id VARCHAR(200),
    request_id VARCHAR(200),
    ip_address INET,
    outcome VARCHAR(32) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_audit_events_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT ck_audit_events_outcome
        CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILED'))
);

CREATE INDEX ix_conversations_customer_recent
    ON conversations(tenant_id, customer_id, last_message_at DESC);
CREATE INDEX ix_conversation_messages_recent
    ON conversation_messages(tenant_id, conversation_id, sequence_no DESC);
CREATE INDEX ix_knowledge_documents_status
    ON knowledge_documents(tenant_id, status, updated_at DESC);
CREATE INDEX ix_knowledge_versions_status
    ON knowledge_document_versions(tenant_id, ingestion_status, created_at);
CREATE INDEX ix_knowledge_chunks_status
    ON knowledge_chunks(tenant_id, index_status, updated_at);
CREATE INDEX ix_ingestion_jobs_available
    ON knowledge_ingestion_jobs(status, available_at)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX ix_answer_records_conversation
    ON answer_records(tenant_id, conversation_id, created_at DESC);
CREATE INDEX ix_feedback_created
    ON answer_feedback(tenant_id, created_at DESC);
CREATE INDEX ix_evaluation_reports_created
    ON evaluation_reports(tenant_id, evaluation_type, created_at DESC);
CREATE INDEX ix_retrieval_traces_conversation
    ON retrieval_traces(tenant_id, conversation_id, created_at DESC);
CREATE INDEX ix_outbox_events_pending
    ON outbox_events(status, available_at, created_at)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX ix_audit_events_resource
    ON audit_events(tenant_id, resource_type, resource_id, occurred_at DESC);
CREATE INDEX ix_audit_events_request
    ON audit_events(tenant_id, request_id)
    WHERE request_id IS NOT NULL;

