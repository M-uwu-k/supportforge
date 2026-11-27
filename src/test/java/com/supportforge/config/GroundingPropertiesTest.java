package com.supportforge.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GroundingPropertiesTest {

    @Test
    void suppliesEvidenceDefaults() {
        GroundingProperties properties =
                new GroundingProperties(0, 0, 0.0, 0.0, null);
        assertEquals(5, properties.maximumEvidence());
        assertEquals(1, properties.minimumEvidence());
        assertEquals(0.12, properties.minimumRerankScore());
        assertEquals(0.30, properties.minimumQueryCoverage());
    }

    @Test
    void rejectsImpossibleEvidenceAndProbability() {
        assertThrows(IllegalArgumentException.class,
                () -> new GroundingProperties(2, 3, 0.1, 0.3, "refuse"));
        assertThrows(IllegalArgumentException.class,
                () -> new GroundingProperties(2, 1, 1.1, 0.3, "refuse"));
    }
}
