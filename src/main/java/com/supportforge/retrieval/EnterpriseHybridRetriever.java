package com.supportforge.retrieval;

import com.supportforge.config.HybridRetrievalProperties;
import com.supportforge.config.QdrantProperties;
import com.supportforge.embedding.EmbeddingBatch;
import com.supportforge.embedding.EmbeddingProvider;
import com.supportforge.grounding.PostgresEvidencePolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** 编码一次，融合召回后以数据库确认有效版本；搜索与聊天在进入精排前共用此入口。 */
@Service
public class EnterpriseHybridRetriever {

    private final EmbeddingProvider embeddingProvider;
    private final HybridRetrievalPort retrievalPort;
    private final QdrantProperties qdrant;
    private final HybridRetrievalProperties properties;
    private PostgresEvidencePolicy evidencePolicy;

    @Autowired
    void configureEvidencePolicy(ObjectProvider<PostgresEvidencePolicy> policy) {
        this.evidencePolicy = policy.getIfAvailable();
    }

    public EnterpriseHybridRetriever(
            EmbeddingProvider embeddingProvider,
            HybridRetrievalPort retrievalPort,
            QdrantProperties qdrant,
            HybridRetrievalProperties properties
    ) {
        this.embeddingProvider = embeddingProvider;
        this.retrievalPort = retrievalPort;
        this.qdrant = qdrant;
        this.properties = properties;
    }

    public List<HybridSearchHit> search(
            UUID tenantId,
            String query,
            Set<String> categories,
            Integer requestedLimit
    ) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("检索问题不能为空");
        }
        int limit = properties.normalizeLimit(requestedLimit);
        EmbeddingBatch batch = embeddingProvider.embedQuery(query.strip());
        if (batch.vectors().size() != 1
                || batch.dimensions() != qdrant.dimensions()) {
            throw new IllegalStateException("查询Embedding数量或维度不符合检索契约");
        }
        HybridSearchRequest firstRequest = new HybridSearchRequest(
                tenantId,
                query,
                batch.vectors().getFirst().values(),
                categories,
                qdrant.indexVersion(),
                properties.candidateLimit(),
                limit,
                properties.rrfK()
        );
        if (evidencePolicy == null) return retrievalPort.search(firstRequest);

        List<HybridSearchHit> current = new ArrayList<>();
        Set<UUID> checked = new LinkedHashSet<>();
        while (current.size() < limit) {
            List<HybridSearchHit> page = retrievalPort.search(new HybridSearchRequest(
                    firstRequest.tenantId(), firstRequest.queryText(), firstRequest.denseVector(),
                    firstRequest.categories(), firstRequest.indexVersion(), firstRequest.candidateLimit(),
                    limit, firstRequest.rrfK(), checked
            ));
            List<HybridSearchHit> unseen = page.stream()
                    .filter(hit -> !checked.contains(hit.pointId())).toList();
            if (unseen.isEmpty()) break;
            Set<UUID> allowed = evidencePolicy.currentPointIds(tenantId, unseen);
            for (HybridSearchHit hit : unseen) {
                if (checked.add(hit.pointId()) && allowed.contains(hit.pointId()) && current.size() < limit) {
                    current.add(hit);
                }
            }


            if (page.size() < limit) break;
        }
        return List.copyOf(current);
    }
}
