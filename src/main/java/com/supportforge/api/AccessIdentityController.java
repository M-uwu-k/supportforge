package com.supportforge.api;

import com.supportforge.security.AccessTokenAuthenticator;
import com.supportforge.security.TenantContextProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AccessIdentityController {
    private final TenantContextProvider tenants;
    private final AccessTokenAuthenticator tokens;

    public AccessIdentityController(TenantContextProvider tenants, AccessTokenAuthenticator tokens) {
        this.tenants = tenants;
        this.tokens = tokens;
    }

    @GetMapping("/auth/me")
    public Map<String, Object> current() {
        var identity = tenants.current();
        return Map.of("tenant_id", identity.tenantId(), "actor_id", identity.actorId(),
                "roles", identity.roles(), "token_required", tokens.required());
    }
}
