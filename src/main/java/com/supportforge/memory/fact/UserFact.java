package com.supportforge.memory.fact;

import java.time.Instant;
import java.util.UUID;

/** 带证据来源和纠错链的用户事实。 */
public record UserFact(
        UUID id,
        UUID tenantId,
        String userId,
        String key,
        String value,
        UserFactSource source,
        String sourceReference,
        double confidence,
        Instant validFrom,
        Instant expiresAt,
        UUID supersedesFactId
) {
    public UserFact {
        if (id == null || tenantId == null || source == null
                || userId == null || userId.isBlank()
                || key == null || key.isBlank()
                || value == null || value.isBlank()
                || !Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("用户事实字段不合法");
        }
        userId = userId.strip();
        key = key.strip();
        value = value.strip();
        sourceReference = sourceReference == null ? "" : sourceReference.strip();
        validFrom = validFrom == null ? Instant.now() : validFrom;
        if (expiresAt != null && expiresAt.isBefore(validFrom)) {
            throw new IllegalArgumentException("用户事实过期时间不能早于生效时间");
        }
    }

    public boolean activeAt(Instant now) {
        return expiresAt == null || expiresAt.isAfter(now);
    }
}
