package com.supportforge.audit;

import com.supportforge.security.RequestIdentity;
import com.supportforge.security.TenantContextProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditEventService {
    private final AuditEventPort port;
    private final TenantContextProvider context;

    public AuditEventService(AuditEventPort port, TenantContextProvider context) {
        this.port = port;
        this.context = context;
    }

    public void record(
            String action, String resourceType, String resourceId,
            String outcome, Map<String, Object> details
    ) {
        RequestIdentity identity = context.current();
        port.append(new AuditEventRecord(
                UUID.randomUUID(), identity.tenantId(), identity.actorId(), action,
                resourceType, resourceId, identity.requestId(), outcome,
                details == null ? Map.of() : Map.copyOf(details), Instant.now()
        ));
    }

    public List<AuditEventRecord> recent(int limit) {
        return port.recent(context.tenantId(), Math.min(Math.max(limit, 1), 200));
    }
}
