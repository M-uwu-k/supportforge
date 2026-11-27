SET search_path TO supportforge, public;

CREATE TABLE memory_episodes (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    summary_version INTEGER NOT NULL,
    content TEXT NOT NULL,
    importance NUMERIC(6, 5) NOT NULL DEFAULT 1.0,
    source_from_sequence BIGINT NOT NULL,
    source_to_sequence BIGINT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_memory_episodes_summary
        UNIQUE (tenant_id, conversation_id, summary_version),
    CONSTRAINT fk_memory_episodes_customer
        FOREIGN KEY (tenant_id, customer_id)
        REFERENCES customer_identities(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_episodes_conversation
        FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES conversations(tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_memory_episodes_importance
        CHECK (importance >= 0 AND importance <= 1),
    CONSTRAINT ck_memory_episodes_sequence
        CHECK (source_from_sequence >= 1 AND source_to_sequence >= source_from_sequence)
);

CREATE INDEX ix_memory_episodes_customer_recent
    ON memory_episodes(tenant_id, customer_id, created_at DESC);
