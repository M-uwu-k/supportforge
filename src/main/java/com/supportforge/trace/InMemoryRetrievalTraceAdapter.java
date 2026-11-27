package com.supportforge.trace;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(
        prefix = "supportforge.features", name = "postgres-persistence",
        havingValue = "false", matchIfMissing = true
)
public class InMemoryRetrievalTraceAdapter implements RetrievalTracePort {
    private final Map<UUID, RetrievalTrace> traces = new ConcurrentHashMap<>();

    @Override
    public void save(RetrievalTrace trace) {
        traces.put(trace.id(), trace);
    }

    @Override
    public Optional<RetrievalTrace> find(UUID tenantId, UUID traceId) {
        return Optional.ofNullable(traces.get(traceId))
                .filter(trace -> trace.tenantId().equals(tenantId));
    }
}
