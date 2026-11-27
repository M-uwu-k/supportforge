package com.supportforge.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEventRecord(
        UUID id, UUID tenantId, String actorId, String action,
        String resourceType, String resourceId, String requestId,
        String outcome, Map<String, Object> details, Instant occurredAt
) { }
