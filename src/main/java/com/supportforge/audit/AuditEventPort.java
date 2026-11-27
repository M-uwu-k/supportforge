package com.supportforge.audit;

import java.util.List;
import java.util.UUID;

public interface AuditEventPort {
    void append(AuditEventRecord event);
    List<AuditEventRecord> recent(UUID tenantId, int limit);
}
