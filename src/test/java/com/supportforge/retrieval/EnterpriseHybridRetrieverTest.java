package com.supportforge.retrieval;

import com.supportforge.config.EmbeddingProperties;
import com.supportforge.config.HybridRetrievalProperties;
import com.supportforge.config.QdrantProperties;
import com.supportforge.embedding.MockQwen3EmbeddingProvider;
import com.supportforge.grounding.PostgresEvidencePolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class EnterpriseHybridRetrieverTest {

    @Test
    void embedsQueryOnceAndBuildsVersionedFilteredRequest() {
        AtomicReference<HybridSearchRequest> captured = new AtomicReference<>();
        HybridRetrievalPort port = request -> {
            captured.set(request);
            return List.of();
        };
        EnterpriseHybridRetriever retriever = new EnterpriseHybridRetriever(
                new MockQwen3EmbeddingProvider(embedding()),
                port,
                qdrant(),
                new HybridRetrievalProperties(30, 6, 20, 60)
        );
        UUID tenant = UUID.randomUUID();

        retriever.search(tenant, " 退款多久到账 ", Set.of("billing"), 4);

        HybridSearchRequest request = captured.get();
        assertEquals(tenant, request.tenantId());
        assertEquals("退款多久到账", request.queryText());
        assertEquals(Set.of("billing"), request.categories());
        assertEquals("index-v1", request.indexVersion());
        assertEquals(30, request.candidateLimit());
        assertEquals(4, request.limit());
        assertEquals(4, request.denseVector().size());
    }

    @Test
    void rejectsBlankQueryBeforeCallingProvider() {
        EnterpriseHybridRetriever retriever = new EnterpriseHybridRetriever(
                new MockQwen3EmbeddingProvider(embedding()),
                request -> List.of(), qdrant(),
                new HybridRetrievalProperties(10, 3, 5, 60)
        );
        assertThrows(IllegalArgumentException.class,
                () -> retriever.search(UUID.randomUUID(), " ", Set.of(), null));
    }

    @Test
    void skipsEntireStalePagesAndOnlySendsCurrentCandidatesToReranker() {
        List<HybridSearchHit> stale = List.of(hit("archived"), hit("old-v1"), hit("old-v2"), hit("pending"));
        List<HybridSearchHit> valid = List.of(hit("current-first"), hit("current-second"));
        List<HybridSearchHit> all = new java.util.ArrayList<>(stale);
        all.addAll(valid);
        java.util.concurrent.atomic.AtomicInteger pages = new java.util.concurrent.atomic.AtomicInteger();
        var embedding = spy(new MockQwen3EmbeddingProvider(embedding()));
        var policy = mock(PostgresEvidencePolicy.class);
        when(policy.currentPointIds(any(), anyList())).thenAnswer(call -> {
            List<HybridSearchHit> batch = call.getArgument(1);
            return batch.stream().filter(valid::contains).map(HybridSearchHit::pointId)
                    .collect(java.util.stream.Collectors.toSet());
        });
        var retrieval = new HybridRetrievalProperties(30, 6, 20, 60);
        var retriever = new EnterpriseHybridRetriever(embedding, request -> {
            pages.incrementAndGet();
            return all.stream().filter(hit -> !request.excludedPointIds().contains(hit.pointId()))
                    .limit(request.limit()).toList();
        }, qdrant(), retrieval);
        configure(retriever, policy);
        var service = new com.supportforge.reranker.EnterpriseRerankingService(retriever,
                (query, documents) -> {
                    assertEquals(List.of("current-first", "current-second"), documents);
                    return new com.supportforge.reranker.RerankBatch("real-protocol", "v1", List.of(
                            new com.supportforge.reranker.RerankScore(0, 0.9),
                            new com.supportforge.reranker.RerankScore(1, 0.8)));
                }, new com.supportforge.config.RerankerProperties("mock", "model", "v1",
                "http://localhost", "", 10, 2, 0.0, Duration.ofSeconds(1), true), retrieval);

        var result = service.search(UUID.randomUUID(), "query", Set.of("billing"), 1);

        assertEquals("current-first", result.getFirst().content());
        assertEquals(3, pages.get());
        verify(embedding, times(1)).embedQuery("query");
    }

    @Test
    void returnsEmptyWhenAllStoredPointsAreArchivedAndDoesNotLoopOnRepeatedPage() {
        var policy = mock(PostgresEvidencePolicy.class);
        when(policy.currentPointIds(any(), anyList())).thenReturn(Set.of());
        HybridSearchHit archived = hit("archived");
        var requests = new java.util.ArrayList<HybridSearchRequest>();
        var retriever = new EnterpriseHybridRetriever(new MockQwen3EmbeddingProvider(embedding()), request -> {
            requests.add(request);
            return List.of(archived);
        }, qdrant(), new HybridRetrievalProperties(10, 3, 5, 60));
        configure(retriever, policy);

        assertTrue(retriever.search(UUID.randomUUID(), "query", Set.of(), 1).isEmpty());
        assertEquals(2, requests.size());
        assertEquals(Set.of(archived.pointId()), requests.getLast().excludedPointIds());
    }

    @Test
    void doesNotReturnUncheckedIndexResultsWhenDatabaseValidationFails() {
        var policy = mock(PostgresEvidencePolicy.class);
        when(policy.currentPointIds(any(), anyList())).thenThrow(new IllegalStateException("database unavailable"));
        var retriever = new EnterpriseHybridRetriever(new MockQwen3EmbeddingProvider(embedding()),
                request -> List.of(hit("unchecked")), qdrant(), new HybridRetrievalProperties(10, 3, 5, 60));
        configure(retriever, policy);
        assertThrows(IllegalStateException.class,
                () -> retriever.search(UUID.randomUUID(), "query", Set.of(), 2));
    }

    @SuppressWarnings("unchecked")
    private void configure(EnterpriseHybridRetriever retriever, PostgresEvidencePolicy policy) {
        ObjectProvider<PostgresEvidencePolicy> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(policy);
        retriever.configureEvidencePolicy(provider);
    }

    private HybridSearchHit hit(String content) {
        return new HybridSearchHit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                content, 0, content, "a".repeat(64), "billing", "index-v1", 0.04);
    }

    private EmbeddingProperties embedding() {
        return new EmbeddingProperties(
                "mock", "model", "model-v1", "http://localhost", "",
                4, 10, Duration.ofSeconds(1), true, "instruction"
        );
    }

    private QdrantProperties qdrant() {
        return new QdrantProperties(
                "memory", "http://localhost:6333", "", "knowledge",
                "dense", "index-v1", 4, "Cosine",
                1, 1, 1, 10, Duration.ofSeconds(1)
        );
    }
}
