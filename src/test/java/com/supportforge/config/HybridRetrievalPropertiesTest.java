package com.supportforge.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HybridRetrievalPropertiesTest {

    @Test
    void suppliesEnterpriseSafeDefaultsAndNormalizesLimit() {
        HybridRetrievalProperties properties =
                new HybridRetrievalProperties(0, 0, 0, 0);

        assertEquals(100, properties.candidateLimit());
        assertEquals(8, properties.normalizeLimit(null));
        assertEquals(12, properties.normalizeLimit(12));
        assertEquals(60, properties.rrfK());
    }

    @Test
    void rejectsLimitBeyondConfiguredBoundary() {
        HybridRetrievalProperties properties =
                new HybridRetrievalProperties(20, 5, 10, 60);

        assertThrows(IllegalArgumentException.class,
                () -> properties.normalizeLimit(11));
        assertThrows(IllegalArgumentException.class,
                () -> new HybridRetrievalProperties(5, 6, 10, 60));
    }
}
