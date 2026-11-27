package com.supportforge.trace;

import java.util.Optional;
import java.util.UUID;

public interface RetrievalTracePort {
    void save(RetrievalTrace trace);

    Optional<RetrievalTrace> find(UUID tenantId, UUID traceId);
}
