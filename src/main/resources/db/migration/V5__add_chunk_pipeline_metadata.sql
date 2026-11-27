-- 第7步：记录分块器版本和清洗后字符偏移，使分块可审计、可复现、可重建。
ALTER TABLE knowledge_document_versions
    ADD COLUMN chunker_version VARCHAR(100);

ALTER TABLE knowledge_chunks
    ADD COLUMN chunker_version VARCHAR(100),
    ADD COLUMN start_offset INTEGER,
    ADD COLUMN end_offset INTEGER;

ALTER TABLE knowledge_chunks
    ADD CONSTRAINT ck_knowledge_chunks_offsets
        CHECK (
            (start_offset IS NULL AND end_offset IS NULL)
            OR (
                start_offset >= 0
                AND end_offset > start_offset
            )
        );

CREATE INDEX ix_knowledge_chunks_version_status
    ON knowledge_chunks(tenant_id, document_version_id, index_status, chunk_index);
