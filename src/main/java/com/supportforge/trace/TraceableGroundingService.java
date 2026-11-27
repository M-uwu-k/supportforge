package com.supportforge.trace;

import com.supportforge.config.EmbeddingProperties;
import com.supportforge.config.RerankerProperties;
import com.supportforge.grounding.GroundingDecision;
import com.supportforge.grounding.GroundingEvidence;
import com.supportforge.grounding.GroundingHarness;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 在Grounding策略外记录耗时、模型、过滤条件和稳定证据引用。 */
@Service
public class TraceableGroundingService {

    private final GroundingHarness harness;
    private final RetrievalTracePort traces;
    private final EmbeddingProperties embedding;
    private final RerankerProperties reranker;

    public TraceableGroundingService(
            GroundingHarness harness,
            RetrievalTracePort traces,
            EmbeddingProperties embedding,
            RerankerProperties reranker
    ) {
        this.harness = harness;
        this.traces = traces;
        this.embedding = embedding;
        this.reranker = reranker;
    }

    public GroundingDecision ground(
            UUID tenantId,
            String query,
            Set<String> categories
    ) {
        return ground(tenantId, query, query, categories);
    }

    /** 原始问题用于审计，补全后的独立问题用于检索、精排和证据覆盖检查。 */
    public GroundingDecision ground(UUID tenantId, String originalQuery, String retrievalQuery, Set<String> categories) {
        long started = System.nanoTime();
        GroundingDecision decision = harness.ground(tenantId, retrievalQuery, categories);
        long latencyMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        UUID traceId = UUID.randomUUID();
        List<RetrievalTraceEvidence> evidence = decision.evidence().stream()
                .map(TraceableGroundingService::traceEvidence)
                .toList();
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("categories", categories == null
                ? List.of() : categories.stream().sorted().toList());
        filters.put("allowed", decision.allowed());
        filters.put("refusal_reason", decision.refusalReason().name());
        filters.put("query_coverage", decision.queryCoverage());
        filters.put("retrieval_query", retrievalQuery);
        traces.save(new RetrievalTrace(
                traceId, tenantId, originalQuery.strip(), filters,
                evidence, decision.allowed() ? evidence : List.of(),
                embedding.model() + "@" + embedding.modelVersion(),
                reranker.model() + "@" + reranker.modelVersion(),
                latencyMs, Instant.now()
        ));
        return decision.withTraceId(traceId);
    }

    private static RetrievalTraceEvidence traceEvidence(GroundingEvidence item) {
        return new RetrievalTraceEvidence(
                item.citationId(), item.pointId(), item.documentId(),
                item.documentVersionId(), item.chunkIndex(),
                item.contentSha256(), item.retrievalScore(), item.rerankScore()
        );
    }
}
