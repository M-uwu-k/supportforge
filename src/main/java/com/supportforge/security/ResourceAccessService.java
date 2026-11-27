package com.supportforge.security;

import com.supportforge.audit.AuditEventService;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ResourceAccessService {
    private final ResourceAclPort acl;
    private final TenantContextProvider context;
    private final AuditEventService audit;
    private org.springframework.context.ApplicationEventPublisher events;

    @org.springframework.beans.factory.annotation.Autowired
    void configureEvents(org.springframework.context.ApplicationEventPublisher events) { this.events = events; }

    public ResourceAccessService(
            ResourceAclPort acl, TenantContextProvider context, AuditEventService audit
    ) {
        this.acl = acl;
        this.context = context;
        this.audit = audit;
    }

    public void grant(
            String resourceType, String resourceId,
            String principalId, AclPermission permission
    ) {
        RequestIdentity actor = context.current();
        acl.grant(
                actor.tenantId(), resourceType, resourceId,
                principalId, permission, actor.actorId()
        );
        if (events != null) events.publishEvent(new PermissionChanged(actor.tenantId(), resourceType, resourceId));
        audit.record(
                "ACL_GRANT", resourceType, resourceId, "SUCCESS",
                Map.of("principal_id", principalId, "permission", permission.name())
        );
    }

    public boolean allowed(String resourceType, String resourceId, AclPermission permission) {
        RequestIdentity actor = context.current();
        return actor.roles().contains(EnterpriseRole.ADMIN)
                || acl.allowed(
                        actor.tenantId(), resourceType, resourceId,
                        actor.actorId(), permission
                );
    }

    public record PermissionChanged(java.util.UUID tenantId, String resourceType, String resourceId) { }
}
