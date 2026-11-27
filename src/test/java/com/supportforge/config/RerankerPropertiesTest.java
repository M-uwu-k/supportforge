package com.supportforge.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RerankerPropertiesTest {

    @Test
    void suppliesModelAndBatchDefaults() {
        RerankerProperties properties = new RerankerProperties(
                null, null, null, null, null,
                0, 0, 0.0, null, true
        );
        assertEquals("mock", properties.provider());
        assertEquals("Qwen/Qwen3-Reranker-0.6B", properties.model());
        assertEquals(32, properties.batchSize());
        assertEquals(30, properties.candidateLimit());
    }

    @Test
    void rejectsUnsupportedProviderAndScore() {
        assertThrows(IllegalArgumentException.class, () -> new RerankerProperties(
                "unknown", "model", "v1", "http://localhost", "",
                1, 1, 0.0, null, true
        ));
        assertThrows(IllegalArgumentException.class, () -> new RerankerProperties(
                "mock", "model", "v1", "http://localhost", "",
                1, 1, 1.1, null, true
        ));
    }
}
