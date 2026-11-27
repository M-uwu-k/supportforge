-- 第6步：知识版本关联S3/MinIO原始文件。原有checksum_sha256继续表示规范化文档内容，
-- raw_checksum_sha256单独表示上传字节，避免把“解析后内容相同”和“原文件完全相同”混为一谈。
ALTER TABLE knowledge_document_versions
    ADD COLUMN raw_checksum_sha256 CHAR(64),
    ADD COLUMN raw_size_bytes BIGINT,
    ADD COLUMN source_filename TEXT;

ALTER TABLE knowledge_document_versions
    ADD CONSTRAINT ck_knowledge_document_versions_raw_checksum
        CHECK (
            raw_checksum_sha256 IS NULL
            OR raw_checksum_sha256 ~ '^[0-9a-f]{64}$'
        ),
    ADD CONSTRAINT ck_knowledge_document_versions_raw_size
        CHECK (raw_size_bytes IS NULL OR raw_size_bytes >= 0);

CREATE INDEX ix_knowledge_document_versions_object_key
    ON knowledge_document_versions(tenant_id, object_key)
    WHERE object_key IS NOT NULL;
