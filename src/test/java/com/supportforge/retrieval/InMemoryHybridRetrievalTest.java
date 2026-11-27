package com.supportforge.retrieval;

import com.supportforge.config.QdrantProperties;
import com.supportforge.vector.InMemoryVectorIndexAdapter;
import com.supportforge.vector.KnowledgeVectorPoint;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryHybridRetrievalTest {

    @Test
    void fusesDenseAndLexicalRanksWhileEnforcingMetadataFilters() {
        InMemoryVectorIndexAdapter adapter = new InMemoryVectorIndexAdapter(
                new QdrantProperties(
                        "memory", "http://localhost:6333", "", "knowledge",
                        "dense", "index-v1", 3, "Cosine",
                        1, 1, 1, 10, Duration.ofSeconds(1)
                )
        );
        UUID tenant = UUID.randomUUID();
        KnowledgeVectorPoint lexicalWinner = point(
                tenant, "退款通常三个工作日到账", "billing",
                List.of(0.0f, 1.0f, 0.0f)
        );
        KnowledgeVectorPoint filteredOtherTenant = point(
                UUID.randomUUID(), "退款立即到账", "billing",
                List.of(1.0f, 0.0f, 0.0f)
        );
        adapter.upsert(List.of(lexicalWinner, filteredOtherTenant));

        List<HybridSearchHit> result = adapter.search(new HybridSearchRequest(
                tenant, "退款多久到账", List.of(1.0f, 0.0f, 0.0f),
                Set.of("billing"), "index-v1", 10, 5, 60
        ));

        assertEquals(1, result.size());
        assertEquals(lexicalWinner.pointId(), result.getFirst().pointId());
        assertEquals("退款通常三个工作日到账", result.getFirst().content());
    }

    private KnowledgeVectorPoint point(
            UUID tenant,
            String content,
            String category,
            List<Float> vector
    ) {
        return new KnowledgeVectorPoint(
                UUID.randomUUID(), tenant, UUID.randomUUID(), UUID.randomUUID(),
                "external", 0, content,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                        + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                category, "index-v1", "model", "model-v1", vector, true
        );
    }
}
