package com.supportforge.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SloMetricsServiceTest {
    @Test
    void calculatesAvailabilityAndLatency() {
        SloMetricsService service = new SloMetricsService(new SloProperties(0.99, 100));
        service.record(200, 50);
        service.record(500, 200);

        SloSnapshot snapshot = service.snapshot();

        assertEquals(2, snapshot.requests());
        assertEquals(0.5, snapshot.availability());
        assertEquals(125, snapshot.averageLatencyMs());
        assertFalse(snapshot.availabilityMet());
        assertFalse(snapshot.averageLatencyMet());
    }
}
