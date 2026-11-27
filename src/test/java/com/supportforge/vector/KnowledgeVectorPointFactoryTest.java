package com.supportforge.vector;

import com.supportforge.config.QdrantProperties;
import com.supportforge.embedding.EmbeddedKnowledgeChunk;
import com.supportforge.knowledge.ingestion.KnowledgeChunk;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KnowledgeVectorPointFactoryTest {

    @Test
    void createsStableIdsAndChangesThemWithIndexVersion() {
        UUID tenant = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        EmbeddedKnowledgeChunk chunk = embedded(4);

        KnowledgeVectorPoint first = factory("index-v1", 4).create(
                tenant, document, version, "refund", "billing", chunk
        );
        KnowledgeVectorPoint replay = factory("index-v1", 4).create(
                tenant, document, version, "refund", "billing", chunk
        );
        KnowledgeVectorPoint nextGeneration = factory("index-v2", 4).create(
                tenant, document, version, "refund", "billing", chunk
        );

        assertEquals(first.pointId(), replay.pointId());
        assertNotEquals(first.pointId(), nextGeneration.pointId());
        assertEquals("index-v1", first.indexVersion());
    }

    @Test
    void rejectsEmbeddingAndCollectionDimensionDrift() {
        assertThrows(
                IllegalArgumentException.class,
                () -> factory("index-v1", 8).create(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        "refund", "billing", embedded(4)
                )
        );
    }

    private KnowledgeVectorPointFactory factory(
            String indexVersion,
            int dimensions
    ) {
        return new KnowledgeVectorPointFactory(new QdrantProperties(
                "memory", "http://localhost:6333", "",
                "knowledge", "dense", indexVersion, dimensions,
                "Cosine", 1, 1, 1, 10,
                Duration.ofSeconds(1)
        ));
    }

    private EmbeddedKnowledgeChunk embedded(int dimensions) {
        KnowledgeChunk chunk = new KnowledgeChunk(
                0,
                "重复扣款可以申请核查",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                        + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                10,
                0,
                10
        );
        return new EmbeddedKnowledgeChunk(
                chunk,
                java.util.Collections.nCopies(dimensions, 0.5f),
                "Qwen/Qwen3-Embedding-0.6B",
                "qwen3-v1",
                dimensions
        );
    }
}
