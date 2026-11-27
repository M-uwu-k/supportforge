package com.supportforge.trace;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 一次企业检索从请求到Grounding判定的审计记录。 */
public record RetrievalTrace(
        UUID id,
        UUID tenantId,
        String originalQuery,
        Map<String, Object> filters,
        List<RetrievalTraceEvidence> recalledChunks,
        List<RetrievalTraceEvidence> selectedEvidence,
        String embeddingModel,
        String rerankerModel,
        long retrievalLatencyMs,
        Instant createdAt
) {
    public RetrievalTrace {
        if (id == null || tenantId == null || originalQuery == null
                || originalQuery.isBlank() || retrievalLatencyMs < 0) {
            throw new IllegalArgumentException("检索轨迹关键字段非法");
        }
        filters = Map.copyOf(filters);
        recalledChunks = List.copyOf(recalledChunks);
        selectedEvidence = List.copyOf(selectedEvidence);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
