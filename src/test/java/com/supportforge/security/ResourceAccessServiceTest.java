package com.supportforge.security;

import com.supportforge.audit.AuditEventService;
import com.supportforge.audit.InMemoryAuditEventAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceAccessServiceTest {
    private final UUID tenant = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void grantsWithinTenantAndDoesNotLeakToAnotherTenant() {
        InMemoryResourceAclAdapter adapter = new InMemoryResourceAclAdapter();
        TenantContextProvider context = new TenantContextProvider(tenant.toString());
        ResourceAccessService service = new ResourceAccessService(
                adapter, context,
                new AuditEventService(new InMemoryAuditEventAdapter(), context)
        );
        TenantContext.set(new RequestIdentity(
                tenant, "admin", Set.of(EnterpriseRole.ADMIN), "request-1"
        ));
        service.grant("KNOWLEDGE", "document-1", "agent-1", AclPermission.READ);

        TenantContext.set(new RequestIdentity(
                tenant, "agent-1", Set.of(EnterpriseRole.AGENT), "request-2"
        ));
        assertTrue(service.allowed("KNOWLEDGE", "document-1", AclPermission.READ));

        TenantContext.set(new RequestIdentity(
                UUID.randomUUID(), "agent-1", Set.of(EnterpriseRole.AGENT), "request-3"
        ));
        assertFalse(service.allowed("KNOWLEDGE", "document-1", AclPermission.READ));
    }
}
