package com.supportforge.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmbeddingPropertiesTest {

    @Test
    void suppliesQwen3SafeDefaults() {
        EmbeddingProperties properties = new EmbeddingProperties(
                null, null, null, null, null,
                0, 0, null, true, null
        );

        assertEquals("mock", properties.provider());
        assertEquals("Qwen/Qwen3-Embedding-0.6B",
                properties.model());
        assertEquals(1024, properties.dimensions());
        assertEquals(10, properties.batchSize());
        assertEquals(Duration.ofSeconds(30), properties.timeout());
    }

    @Test
    void rejectsInvalidProviderAndDimensions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EmbeddingProperties(
                        "unknown", "model", "v1",
                        "http://localhost", "", 1024, 10,
                        Duration.ofSeconds(1), true, "instruction"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new EmbeddingProperties(
                        "mock", "model", "v1",
                        "http://localhost", "", 9000, 10,
                        Duration.ofSeconds(1), true, "instruction"
                )
        );
    }
}
