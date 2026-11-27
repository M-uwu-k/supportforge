package com.supportforge.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(
        prefix = "supportforge.features", name = "postgres-persistence",
        havingValue = "false", matchIfMissing = true
)
public class InMemoryResourceAclAdapter implements ResourceAclPort {
    private final Set<Grant> grants = ConcurrentHashMap.newKeySet();

    @Override
    public void grant(
            UUID tenantId, String resourceType, String resourceId,
            String principalId, AclPermission permission, String grantedBy
    ) {
        grants.add(new Grant(tenantId, resourceType, resourceId, principalId, permission));
    }

    @Override
    public boolean allowed(
            UUID tenantId, String resourceType, String resourceId,
            String principalId, AclPermission permission
    ) {
        return grants.contains(new Grant(
                tenantId, resourceType, resourceId, principalId, permission
        ));
    }

    private record Grant(
            UUID tenant, String type, String resource, String principal, AclPermission permission
    ) { }
}
