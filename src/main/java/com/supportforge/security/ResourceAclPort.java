package com.supportforge.security;

import java.util.UUID;

public interface ResourceAclPort {
    void grant(
            UUID tenantId, String resourceType, String resourceId,
            String principalId, AclPermission permission, String grantedBy
    );

    boolean allowed(
            UUID tenantId, String resourceType, String resourceId,
            String principalId, AclPermission permission
    );
}
