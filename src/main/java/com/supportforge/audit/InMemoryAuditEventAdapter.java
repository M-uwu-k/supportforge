package com.supportforge.audit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        prefix = "supportforge.features", name = "postgres-persistence",
        havingValue = "false", matchIfMissing = true
)
public class InMemoryAuditEventAdapter implements AuditEventPort {
    private final List<AuditEventRecord> events = new ArrayList<>();

    @Override
    public synchronized void append(AuditEventRecord event) {
        events.add(event);
    }

    @Override
    public synchronized List<AuditEventRecord> recent(UUID tenantId, int limit) {
        return events.reversed().stream()
                .filter(event -> event.tenantId().equals(tenantId))
                .limit(limit).toList();
    }
}
