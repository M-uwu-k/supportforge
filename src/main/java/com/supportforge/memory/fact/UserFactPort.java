package com.supportforge.memory.fact;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface UserFactPort {
    UserFact replace(
            UUID tenantId, String userId, String key, String value,
            UserFactSource source, String sourceReference,
            double confidence, Instant expiresAt
    );

    List<UserFact> findActive(UUID tenantId, String userId, Instant now);

    boolean revoke(UUID tenantId, String userId, String key, Instant now);
}
