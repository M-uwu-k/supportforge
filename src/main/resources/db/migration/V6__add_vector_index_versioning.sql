-- 第9步：把可重建向量索引的版本身份记录在PostgreSQL事实源中。
ALTER TABLE knowledge_chunks
    ADD COLUMN index_version VARCHAR(100),
    ADD COLUMN vector_name VARCHAR(100),
    ADD COLUMN indexed_at TIMESTAMPTZ;

CREATE INDEX ix_knowledge_chunks_index_version
    ON knowledge_chunks(tenant_id, index_version, index_status, updated_at);

-- 每次模型/维度/切分规范升级应创建新generation，而不是覆盖旧索引身份。
CREATE TABLE vector_index_generations (
    id UUID PRIMARY KEY,
    index_version VARCHAR(100) NOT NULL UNIQUE,
    collection_name VARCHAR(255) NOT NULL,
    dense_vector_name VARCHAR(255) NOT NULL,
    dimensions INTEGER NOT NULL,
    distance VARCHAR(32) NOT NULL,
    embedding_model VARCHAR(200) NOT NULL,
    embedding_version VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'BUILDING',
    activated_at TIMESTAMPTZ,
    retired_at TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_vector_index_generations_dimensions
        CHECK (dimensions > 0),
    CONSTRAINT ck_vector_index_generations_distance
        CHECK (distance IN ('Cosine', 'Dot', 'Euclid', 'Manhattan')),
    CONSTRAINT ck_vector_index_generations_status
        CHECK (status IN ('BUILDING', 'ACTIVE', 'RETIRED', 'FAILED')),
    CONSTRAINT ck_vector_index_generations_activation
        CHECK (status <> 'ACTIVE' OR activated_at IS NOT NULL),
    CONSTRAINT ck_vector_index_generations_retirement
        CHECK (status <> 'RETIRED' OR retired_at IS NOT NULL)
);

CREATE UNIQUE INDEX uq_vector_index_generations_active
    ON vector_index_generations ((status))
    WHERE status = 'ACTIVE';
