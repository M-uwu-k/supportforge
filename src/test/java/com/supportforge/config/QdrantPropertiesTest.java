package com.supportforge.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QdrantPropertiesTest {

    @Test
    void suppliesSafeLocalDefaults() {
        QdrantProperties properties = new QdrantProperties(
                null, null, null, null, null, null,
                0, null, 0, 0, 0, 0, null
        );

        assertEquals("memory", properties.provider());
        assertEquals("supportforge_knowledge_v1", properties.collection());
        assertEquals("qwen3_dense", properties.denseVectorName());
        assertEquals(1024, properties.dimensions());
        assertEquals("Cosine", properties.distance());
        assertEquals(Duration.ofSeconds(10), properties.timeout());
    }

    @Test
    void rejectsUnsafeNamesAndImpossibleConsistency() {
        assertThrows(
                IllegalArgumentException.class,
                () -> properties("../escape", 1, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> properties("valid", 1, 2)
        );
    }

    private QdrantProperties properties(
            String collection,
            int replicas,
            int consistency
    ) {
        return new QdrantProperties(
                "qdrant", "http://localhost:6333", "",
                collection, "dense", "v1", 4, "Cosine",
                1, replicas, consistency, 10,
                Duration.ofSeconds(1)
        );
    }
}
