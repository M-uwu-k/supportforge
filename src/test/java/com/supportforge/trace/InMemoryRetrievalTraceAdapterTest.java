package com.supportforge.trace;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryRetrievalTraceAdapterTest {

    @Test
    void onlyReturnsTraceToOwningTenant() {
        InMemoryRetrievalTraceAdapter adapter = new InMemoryRetrievalTraceAdapter();
        UUID tenant = UUID.randomUUID();
        RetrievalTrace trace = new RetrievalTrace(
                UUID.randomUUID(), tenant, "query", Map.of(),
                List.of(), List.of(), "embedding", "reranker", 12, Instant.now()
        );
        adapter.save(trace);

        assertTrue(adapter.find(tenant, trace.id()).isPresent());
        assertTrue(adapter.find(UUID.randomUUID(), trace.id()).isEmpty());
    }
}
