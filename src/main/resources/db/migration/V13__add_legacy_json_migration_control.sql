SET search_path TO supportforge, public;

-- 每次导入都有不可变来源校验和和明确状态；JSON源文件本身不会被删除或改写。
CREATE TABLE legacy_migration_runs (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    dataset_type VARCHAR(32) NOT NULL,
    source_file TEXT NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    source_size_bytes BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    discovered_items INTEGER NOT NULL DEFAULT 0,
    imported_items INTEGER NOT NULL DEFAULT 0,
    skipped_items INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    rolled_back_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_legacy_migration_runs_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT ck_legacy_migration_runs_type
        CHECK (dataset_type IN ('MEMORY', 'KNOWLEDGE', 'INTENT', 'FEEDBACK', 'EVALUATION')),
    CONSTRAINT ck_legacy_migration_runs_status
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'ROLLED_BACK')),
    CONSTRAINT ck_legacy_migration_runs_hash
        CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_legacy_migration_runs_counts
        CHECK (source_size_bytes >= 0 AND discovered_items >= 0
            AND imported_items >= 0 AND skipped_items >= 0)
);

-- 同一租户、同一类型、同一文件的已完成导入只能有一个；回滚后可重新导入。
CREATE UNIQUE INDEX uq_legacy_migration_completed_source
    ON legacy_migration_runs(tenant_id, dataset_type, source_sha256)
    WHERE status = 'COMPLETED';

CREATE TABLE legacy_migration_items (
    run_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    dataset_type VARCHAR(32) NOT NULL,
    source_key TEXT NOT NULL,
    item_sha256 CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    target_type VARCHAR(80),
    target_id UUID,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, source_key),
    CONSTRAINT fk_legacy_migration_items_run
        FOREIGN KEY (run_id) REFERENCES legacy_migration_runs(id) ON DELETE CASCADE,
    CONSTRAINT fk_legacy_migration_items_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT ck_legacy_migration_items_status
        CHECK (status IN ('IMPORTED', 'SKIPPED_EXISTING', 'ROLLED_BACK')),
    CONSTRAINT ck_legacy_migration_items_hash
        CHECK (item_sha256 ~ '^[0-9a-f]{64}$')
);

-- 同一业务条目即使后来被放入另一个文件，也不能在未回滚时重复导入。
CREATE UNIQUE INDEX uq_legacy_migration_active_item
    ON legacy_migration_items(tenant_id, dataset_type, item_sha256)
    WHERE status = 'IMPORTED';

-- 业务行携带迁移批次来源。回滚只删除本批次创建的行，不猜测、不按时间范围删除。
ALTER TABLE customer_identities
    ADD COLUMN migration_run_id UUID REFERENCES legacy_migration_runs(id) ON DELETE SET NULL;
ALTER TABLE conversations
    ADD COLUMN migration_run_id UUID REFERENCES legacy_migration_runs(id) ON DELETE SET NULL;
ALTER TABLE knowledge_documents
    ADD COLUMN migration_run_id UUID REFERENCES legacy_migration_runs(id) ON DELETE SET NULL;
ALTER TABLE intent_learning_samples
    ADD COLUMN migration_run_id UUID REFERENCES legacy_migration_runs(id) ON DELETE SET NULL;
ALTER TABLE answer_records
    ADD COLUMN migration_run_id UUID REFERENCES legacy_migration_runs(id) ON DELETE SET NULL;
ALTER TABLE evaluation_reports
    ADD COLUMN migration_run_id UUID REFERENCES legacy_migration_runs(id) ON DELETE SET NULL;

CREATE INDEX ix_customer_identities_migration_run ON customer_identities(migration_run_id);
CREATE INDEX ix_conversations_migration_run ON conversations(migration_run_id);
CREATE INDEX ix_knowledge_documents_migration_run ON knowledge_documents(migration_run_id);
CREATE INDEX ix_intent_learning_migration_run ON intent_learning_samples(migration_run_id);
CREATE INDEX ix_answer_records_migration_run ON answer_records(migration_run_id);
CREATE INDEX ix_evaluation_reports_migration_run ON evaluation_reports(migration_run_id);
