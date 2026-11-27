SET search_path TO supportforge, public;

-- 规范化正文是可检索事实数据；原始上传文件在第6步进入对象存储。
ALTER TABLE knowledge_document_versions
    ADD COLUMN content_text TEXT NOT NULL DEFAULT '',
    ADD COLUMN keywords JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE knowledge_document_versions
    ALTER COLUMN content_text DROP DEFAULT;

ALTER TABLE knowledge_document_versions
    ADD CONSTRAINT ck_knowledge_document_versions_keywords_array
        CHECK (jsonb_typeof(keywords) = 'array');

CREATE INDEX ix_knowledge_documents_active
    ON knowledge_documents(tenant_id, updated_at DESC, external_key)
    WHERE status = 'ACTIVE';
