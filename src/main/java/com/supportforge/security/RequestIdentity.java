package com.supportforge.security;

import java.util.Set;
import java.util.UUID;

public record RequestIdentity(
        UUID tenantId,
        String actorId,
        Set<EnterpriseRole> roles,
        String requestId
) {
    public RequestIdentity {
        if (tenantId == null || actorId == null || actorId.isBlank()
                || requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("请求身份不完整");
        }
        actorId = actorId.strip();
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        requestId = requestId.strip();
    }

    public boolean hasAny(Set<EnterpriseRole> expected) {
        return roles.stream().anyMatch(expected::contains);
    }
}
