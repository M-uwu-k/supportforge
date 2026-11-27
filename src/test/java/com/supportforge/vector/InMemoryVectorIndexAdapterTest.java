package com.supportforge.vector;

import com.supportforge.config.QdrantProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryVectorIndexAdapterTest {

    @Test
    void idempotentlyReplacesPointWithSameStableId() {
        InMemoryVectorIndexAdapter adapter = adapter();
        KnowledgeVectorPoint point = point("index-v1", 4);

        adapter.upsert(List.of(point));
        adapter.upsert(List.of(point));

        assertEquals(1, adapter.snapshot().size());
        assertEquals(4, adapter.ensureReady().dimensions());
    }

    @Test
    void rejectsWrongIndexVersionBeforeWriting() {
        InMemoryVectorIndexAdapter adapter = adapter();

        assertThrows(
                IllegalArgumentException.class,
                () -> adapter.upsert(List.of(point("index-v2", 4)))
        );
        assertEquals(0, adapter.snapshot().size());
    }

    @Test
    void excludedPointCannotReappearThroughEitherDenseOrLexicalRecall() {
        InMemoryVectorIndexAdapter adapter = adapter();
        KnowledgeVectorPoint point = point("index-v1", 4);
        adapter.upsert(List.of(point));
        var request = new com.supportforge.retrieval.HybridSearchRequest(point.tenantId(), "正文",
                point.denseVector(), java.util.Set.of(), "index-v1", 10, 1, 60,
                java.util.Set.of(point.pointId()));
        assertEquals(List.of(), adapter.search(request));
    }

    private InMemoryVectorIndexAdapter adapter() {
        return new InMemoryVectorIndexAdapter(properties());
    }

    private QdrantProperties properties() {
        return new QdrantProperties(
                "memory", "http://localhost:6333", "",
                "knowledge", "dense", "index-v1", 4, "Cosine",
                1, 1, 1, 10, Duration.ofSeconds(1)
        );
    }

    static KnowledgeVectorPoint point(String indexVersion, int dimensions) {
        return new KnowledgeVectorPoint(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "refund", 0, "正文",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                        + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "billing", indexVersion, "model", "model-v1",
                java.util.Collections.nCopies(dimensions, 0.5f), true
        );
    }
}
