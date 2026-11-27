-- 第10步：为可靠领取、崩溃恢复、幂等发布和索引任务审计补齐Outbox契约。
ALTER TABLE outbox_events
    ADD COLUMN aggregate_version BIGINT,
    ADD COLUMN topic VARCHAR(255),
    ADD COLUMN partition_key VARCHAR(255),
    ADD COLUMN idempotency_key VARCHAR(255),
    ADD COLUMN lease_owner VARCHAR(200),
    ADD COLUMN lease_until TIMESTAMPTZ,
    ADD COLUMN dead_at TIMESTAMPTZ,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE outbox_events
   SET aggregate_version = event_version,
       topic = 'supportforge.legacy',
       idempotency_key = 'legacy:' || id::text
 WHERE aggregate_version IS NULL
    OR topic IS NULL
    OR idempotency_key IS NULL;

ALTER TABLE outbox_events
    ALTER COLUMN aggregate_version SET NOT NULL,
    ALTER COLUMN topic SET NOT NULL,
    ALTER COLUMN idempotency_key SET NOT NULL;

ALTER TABLE outbox_events
    ADD CONSTRAINT uq_outbox_events_idempotency
        UNIQUE (tenant_id, idempotency_key),
    ADD CONSTRAINT ck_outbox_events_aggregate_version
        CHECK (aggregate_version >= 1),
    ADD CONSTRAINT ck_outbox_events_lease
        CHECK (
            (lease_owner IS NULL AND lease_until IS NULL)
            OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        );

CREATE INDEX ix_outbox_events_claim
    ON outbox_events(status, available_at, lease_until, created_at)
    WHERE status IN ('PENDING', 'FAILED', 'PUBLISHING');

ALTER TABLE knowledge_ingestion_jobs
    ADD COLUMN job_type VARCHAR(100) NOT NULL DEFAULT 'KNOWLEDGE_INDEX',
    ADD COLUMN index_version VARCHAR(100),
    ADD COLUMN idempotency_key VARCHAR(255),
    ADD COLUMN max_attempts INTEGER NOT NULL DEFAULT 10,
    ADD COLUMN completed_at TIMESTAMPTZ;

UPDATE knowledge_ingestion_jobs
   SET idempotency_key = 'legacy:' || id::text,
       index_version = COALESCE(index_version, 'legacy')
 WHERE idempotency_key IS NULL
    OR index_version IS NULL;

ALTER TABLE knowledge_ingestion_jobs
    ALTER COLUMN idempotency_key SET NOT NULL,
    ALTER COLUMN index_version SET NOT NULL,
    DROP CONSTRAINT uq_knowledge_ingestion_jobs_version,
    ADD CONSTRAINT uq_ingestion_jobs_generation
        UNIQUE (tenant_id, document_version_id, index_version),
    ADD CONSTRAINT uq_ingestion_jobs_idempotency
        UNIQUE (tenant_id, idempotency_key),
    ADD CONSTRAINT ck_ingestion_jobs_max_attempts
        CHECK (max_attempts >= 1),
    ADD CONSTRAINT ck_ingestion_jobs_attempt_limit
        CHECK (attempt_count <= max_attempts);

CREATE INDEX ix_ingestion_jobs_claim_v2
    ON knowledge_ingestion_jobs(status, available_at, lease_until, created_at)
    WHERE status IN ('PENDING', 'FAILED', 'RUNNING');
