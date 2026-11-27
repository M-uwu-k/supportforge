package com.supportforge.api;

import com.supportforge.audit.AuditEventRecord;
import com.supportforge.audit.AuditEventService;
import com.supportforge.security.AclPermission;
import com.supportforge.security.EnterpriseRole;
import com.supportforge.security.RequiresAnyRole;
import com.supportforge.security.ResourceAccessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/access")
public class EnterpriseAccessController {
    private final ResourceAccessService access;
    private final AuditEventService audit;

    public EnterpriseAccessController(ResourceAccessService access, AuditEventService audit) {
        this.access = access;
        this.audit = audit;
    }

    @PostMapping("/acl")
    @RequiresAnyRole({EnterpriseRole.ADMIN})
    public void grant(@Valid @RequestBody AclGrantRequest request) {
        access.grant(
                request.resourceType(), request.resourceId(),
                request.principalId(), request.permission()
        );
    }

    @GetMapping("/check")
    @RequiresAnyRole({
            EnterpriseRole.ADMIN, EnterpriseRole.AGENT,
            EnterpriseRole.KNOWLEDGE_EDITOR, EnterpriseRole.AUDITOR
    })
    public AccessDecision check(
            @RequestParam String resourceType,
            @RequestParam String resourceId,
            @RequestParam AclPermission permission
    ) {
        return new AccessDecision(access.allowed(resourceType, resourceId, permission));
    }

    @GetMapping("/audit")
    @RequiresAnyRole({EnterpriseRole.ADMIN, EnterpriseRole.AUDITOR})
    public List<AuditEventRecord> audit(@RequestParam(defaultValue = "50") int limit) {
        return audit.recent(limit);
    }

    public record AclGrantRequest(
            @NotBlank String resourceType,
            @NotBlank String resourceId,
            @NotBlank String principalId,
            @NotNull AclPermission permission
    ) { }

    public record AccessDecision(boolean allowed) { }
}
