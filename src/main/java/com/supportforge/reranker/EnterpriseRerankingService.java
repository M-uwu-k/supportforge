package com.supportforge.reranker;

import com.supportforge.config.HybridRetrievalProperties;
import com.supportforge.config.RerankerProperties;
import com.supportforge.knowledge.KnowledgeBase;
import com.supportforge.knowledge.KnowledgeDocument;
import com.supportforge.retrieval.EnterpriseHybridRetriever;
import com.supportforge.retrieval.HybridSearchHit;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 混合召回后的第二阶段精排；Provider故障时可保留RRF顺序降级。 */
@Service
public class EnterpriseRerankingService {

    private final EnterpriseHybridRetriever retriever;
    private final RerankerProvider provider;
    private final RerankerProperties properties;
    private final HybridRetrievalProperties retrievalProperties;
    private com.supportforge.security.ResourceAccessService access;
    private KnowledgeBase knowledgeBase;

    @org.springframework.beans.factory.annotation.Autowired
    void configureKnowledgeBase(KnowledgeBase knowledgeBase) { this.knowledgeBase = knowledgeBase; }

    @org.springframework.beans.factory.annotation.Autowired
    void configureAccess(com.supportforge.security.ResourceAccessService access) { this.access = access; }

    public EnterpriseRerankingService(
            EnterpriseHybridRetriever retriever,
            RerankerProvider provider,
            RerankerProperties properties,
            HybridRetrievalProperties retrievalProperties
    ) {
        this.retriever = retriever;
        this.provider = provider;
        this.properties = properties;
        this.retrievalProperties = retrievalProperties;
        if (properties.candidateLimit() > retrievalProperties.maximumLimit()) {
            throw new IllegalStateException(
                    "Reranker候选数不能超过检索maximum-limit"
            );
        }
    }

    public List<RerankedSearchHit> search(
            UUID tenantId,
            String query,
            Set<String> categories,
            Integer requestedLimit
    ) {
        int finalLimit = retrievalProperties.normalizeLimit(requestedLimit);
        List<HybridSearchHit> candidates = retriever.search(
                tenantId, query, categories, properties.candidateLimit()
        ).stream().filter(hit -> access == null || access.allowed(
                "knowledge_document", hit.externalDocumentId(), com.supportforge.security.AclPermission.READ
        )).toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        Set<String> ids = candidates.stream().map(HybridSearchHit::externalDocumentId).collect(Collectors.toSet());
        Map<String, KnowledgeDocument> documents = knowledgeBase == null ? Map.of()
                : knowledgeBase.readableDocuments().stream().filter(document -> ids.contains(document.id()))
                .collect(Collectors.toMap(KnowledgeDocument::id, document -> document));
        try {
            RerankBatch batch = provider.rerank(
                    query,

                    candidates.stream().map(hit -> {
                        KnowledgeDocument document = documents.get(hit.externalDocumentId());
                        if (document == null) return hit.content();
                        return "来源标题：" + document.title() + "\n检索关键词："
                                + String.join("、", document.keywords()) + "\n正文：\n" + hit.content();
                    }).toList()
            );
            Map<Integer, RerankScore> scores = batch.scores().stream()
                    .collect(Collectors.toUnmodifiableMap(
                            RerankScore::index, Function.identity()
                    ));
            if (scores.size() != candidates.size()) {
                throw new RerankerProviderException("Reranker结果未覆盖全部候选");
            }
            List<ScoredHit> ranked = new ArrayList<>();
            for (int index = 0; index < candidates.size(); index++) {
                RerankScore score = scores.get(index);
                if (score == null) {
                    throw new RerankerProviderException("Reranker缺少候选序号" + index);
                }
                if (score.score() >= properties.minimumScore()) {
                    ranked.add(new ScoredHit(candidates.get(index), score.score()));
                }
            }
            ranked.sort(Comparator
                    .comparingDouble(ScoredHit::score).reversed()
                    .thenComparing(Comparator.comparingDouble(
                            (ScoredHit value) -> value.hit().score()
                    ).reversed())
                    .thenComparing(value -> value.hit().pointId().toString()));
            List<RerankedSearchHit> result = new ArrayList<>();
            for (int index = 0;
                 index < Math.min(finalLimit, ranked.size()); index++) {
                ScoredHit item = ranked.get(index);
                result.add(RerankedSearchHit.from(
                        item.hit(), item.score(), index + 1,
                        batch.model(), batch.modelVersion(), true
                ));
            }
            return List.copyOf(result);
        } catch (RerankerProviderException exception) {
            if (!properties.failOpen()) {
                throw exception;
            }
            List<RerankedSearchHit> fallback = new ArrayList<>();
            for (int index = 0;
                 index < Math.min(finalLimit, candidates.size()); index++) {
                fallback.add(RerankedSearchHit.from(
                        candidates.get(index), candidates.get(index).score(),
                        index + 1, properties.model(), properties.modelVersion(),
                        false
                ));
            }
            return List.copyOf(fallback);
        }
    }

    private record ScoredHit(HybridSearchHit hit, double score) {
    }
}
