package com.supportforge.indexing;

import com.supportforge.knowledge.ingestion.KnowledgeChunk;

import java.util.List;
import java.util.UUID;

/** 一次已持有数据库租约的知识索引工作单。 */
public record KnowledgeIndexWork(
        UUID jobId,
        UUID eventId,
        UUID tenantId,
        UUID documentId,
        UUID documentVersionId,
        String externalDocumentId,
        String category,
        String indexVersion,
        String owner,
        List<KnowledgeChunk> chunks
) {

    public KnowledgeIndexWork {
        if (jobId == null || eventId == null || tenantId == null
                || documentId == null || documentVersionId == null) {
            throw new IllegalArgumentException("索引工作单UUID不能为空");
        }
        if (externalDocumentId == null || externalDocumentId.isBlank()
                || category == null || category.isBlank()
                || indexVersion == null || indexVersion.isBlank()
                || owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("索引工作单身份字段不能为空");
        }
        chunks = List.copyOf(chunks);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("索引工作单分块不能为空");
        }
    }
}
