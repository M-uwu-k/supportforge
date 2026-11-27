package com.supportforge.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
public class TenantContextProvider {
    private final UUID defaultTenantId;

    public TenantContextProvider(
            @Value("${supportforge.persistence.default-tenant-id:00000000-0000-0000-0000-000000000001}")
            String defaultTenantId
    ) {
        this.defaultTenantId = UUID.fromString(defaultTenantId);
    }

    public RequestIdentity current() {
        return TenantContext.current().orElseGet(() -> new RequestIdentity(
                defaultTenantId, "local-system", Set.of(EnterpriseRole.ADMIN), "local-request"
        ));
    }

    public UUID tenantId() {
        return current().tenantId();
    }
}
