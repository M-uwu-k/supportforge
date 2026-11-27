package com.supportforge.retrieval;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 送往检索基础设施的强类型请求，过滤条件不得由调用方拼接JSON。 */
public record HybridSearchRequest(
        UUID tenantId,
        String queryText,
        List<Float> denseVector,
        Set<String> categories,
        String indexVersion,
        int candidateLimit,
        int limit,
        int rrfK,
        Set<UUID> excludedPointIds,
        RetrievalMode mode
) {


    public HybridSearchRequest(UUID tenantId, String queryText, List<Float> denseVector,
                               Set<String> categories, String indexVersion,
                               int candidateLimit, int limit, int rrfK) {
        this(tenantId, queryText, denseVector, categories, indexVersion,
                candidateLimit, limit, rrfK, Set.of(), RetrievalMode.HYBRID_RRF);
    }

    public HybridSearchRequest(UUID tenantId, String queryText, List<Float> denseVector,
                               Set<String> categories, String indexVersion,
                               int candidateLimit, int limit, int rrfK,
                               Set<UUID> excludedPointIds) {
        this(tenantId, queryText, denseVector, categories, indexVersion,
                candidateLimit, limit, rrfK, excludedPointIds, RetrievalMode.HYBRID_RRF);
    }

    public HybridSearchRequest {
        if (tenantId == null) {
            throw new IllegalArgumentException("检索租户不能为空");
        }
        if (queryText == null || queryText.isBlank()) {
            throw new IllegalArgumentException("检索问题不能为空");
        }
        queryText = queryText.strip();
        denseVector = List.copyOf(denseVector);
        if (denseVector.isEmpty()) {
            throw new IllegalArgumentException("查询稠密向量不能为空");
        }
        for (Float value : denseVector) {
            if (value == null || !Float.isFinite(value)) {
                throw new IllegalArgumentException("查询稠密向量只能包含有限数");
            }
        }
        categories = categories == null ? Set.of() : Set.copyOf(categories);
        excludedPointIds = excludedPointIds == null ? Set.of() : Set.copyOf(excludedPointIds);
        mode = mode == null ? RetrievalMode.HYBRID_RRF : mode;
        for (String category : categories) {
            if (category == null || category.isBlank()) {
                throw new IllegalArgumentException("分类过滤值不能为空");
            }
        }
        if (indexVersion == null || indexVersion.isBlank()) {
            throw new IllegalArgumentException("索引版本不能为空");
        }
        if (candidateLimit < 1 || limit < 1 || limit > candidateLimit) {
            throw new IllegalArgumentException("检索候选数或返回数非法");
        }
        if (rrfK < 1) {
            throw new IllegalArgumentException("RRF常数必须为正数");
        }
    }
}
