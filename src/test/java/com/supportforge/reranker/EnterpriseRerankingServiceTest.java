package com.supportforge.reranker;

import com.supportforge.config.EmbeddingProperties;
import com.supportforge.config.HybridRetrievalProperties;
import com.supportforge.config.QdrantProperties;
import com.supportforge.config.RerankerProperties;
import com.supportforge.embedding.MockQwen3EmbeddingProvider;
import com.supportforge.retrieval.EnterpriseHybridRetriever;
import com.supportforge.retrieval.HybridSearchHit;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class EnterpriseRerankingServiceTest {

    @Test
    void sendsDeclaredKeywordsToRemoteRerankerWhileKeepingSourceContentAndHashUnchanged() {
        HybridSearchHit hit = hit("世界杯巴西赢了", 0.04);
        var service = service(request -> List.of(hit), (query, documents) -> {
            assertEquals("足球", query);
            assertEquals(List.of("来源标题：世界杯\n检索关键词：世界杯、足球\n正文：\n世界杯巴西赢了"), documents);
            return new RerankBatch("qwen", "v1", List.of(new RerankScore(0, 0.9)));
        }, true);
        var knowledge = mock(com.supportforge.knowledge.KnowledgeBase.class);
        when(knowledge.readableDocuments()).thenReturn(List.of(new com.supportforge.knowledge.KnowledgeDocument(
                "external", "世界杯", com.supportforge.knowledge.KnowledgeCategory.GENERAL,
                "世界杯巴西赢了", List.of("世界杯", "足球"))));
        service.configureKnowledgeBase(knowledge);

        RerankedSearchHit result = service.search(UUID.randomUUID(), "足球", Set.of(), 1).getFirst();
        assertEquals(hit.content(), result.content());
        assertEquals(hit.contentSha256(), result.contentSha256());
        assertTrue(result.reranked());
    }

    @Test
    void reordersCandidatesByModelScoreAndKeepsRetrievalScore() {
        List<HybridSearchHit> candidates = List.of(hit("first", 0.04), hit("second", 0.03));
        EnterpriseRerankingService service = service(
                request -> candidates,
                (query, documents) -> new RerankBatch(
                        "qwen", "v1",
                        List.of(new RerankScore(0, 0.1), new RerankScore(1, 0.9))
                ), true
        );

        List<RerankedSearchHit> result = service.search(
                UUID.randomUUID(), "query", Set.of(), 2
        );

        assertEquals("second", result.getFirst().content());
        assertEquals(0.03, result.getFirst().retrievalScore());
        assertEquals(0.9, result.getFirst().rerankScore());
        assertTrue(result.getFirst().reranked());
    }

    @Test
    void fallsBackToRrfOrderWhenProviderFailsAndFailOpenIsEnabled() {
        List<HybridSearchHit> candidates = List.of(hit("first", 0.04), hit("second", 0.03));
        EnterpriseRerankingService service = service(
                request -> candidates,
                (query, documents) -> {
                    throw new RerankerProviderException("timeout");
                }, true
        );

        List<RerankedSearchHit> result = service.search(
                UUID.randomUUID(), "query", Set.of(), 1
        );

        assertEquals("first", result.getFirst().content());
        assertFalse(result.getFirst().reranked());
        assertEquals(result.getFirst().retrievalScore(), result.getFirst().rerankScore());
    }

    private EnterpriseRerankingService service(
            com.supportforge.retrieval.HybridRetrievalPort port,
            RerankerProvider provider,
            boolean failOpen
    ) {
        HybridRetrievalProperties retrieval =
                new HybridRetrievalProperties(100, 8, 50, 60);
        EnterpriseHybridRetriever retriever = new EnterpriseHybridRetriever(
                new MockQwen3EmbeddingProvider(new EmbeddingProperties(
                        "mock", "embedding", "v1", "http://localhost", "",
                        4, 10, Duration.ofSeconds(1), true, "instruction"
                )), port,
                new QdrantProperties(
                        "memory", "http://localhost", "", "knowledge", "dense",
                        "index-v1", 4, "Cosine", 1, 1, 1, 10,
                        Duration.ofSeconds(1)
                ), retrieval
        );
        return new EnterpriseRerankingService(
                retriever, provider,
                new RerankerProperties(
                        "mock", "qwen", "v1", "http://localhost", "",
                        10, 30, 0.0, Duration.ofSeconds(1), failOpen
                ), retrieval
        );
    }

    private HybridSearchHit hit(String content, double score) {
        return new HybridSearchHit(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "external", 0, content,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                        + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "billing", "index-v1", score
        );
    }
}
