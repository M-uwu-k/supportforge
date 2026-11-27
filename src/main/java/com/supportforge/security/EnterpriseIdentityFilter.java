package com.supportforge.security;

import com.supportforge.audit.AuditEventService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(
        prefix = "supportforge.features", name = "multi-tenancy", havingValue = "true"
)
public class EnterpriseIdentityFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(EnterpriseIdentityFilter.class);
    private final AuditEventService audit;
    private AccessTokenAuthenticator tokens;

    @org.springframework.beans.factory.annotation.Autowired
    void configureTokens(AccessTokenAuthenticator tokens) { this.tokens = tokens; }

    public EnterpriseIdentityFilter(AuditEventService audit) {
        this.audit = audit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain
    ) throws ServletException, IOException {
        RequestIdentity identity;
        try {
            identity = identity(request);
        } catch (IllegalArgumentException exception) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, exception.getMessage());
            return;
        }
        TenantContext.set(identity);
        try {
            chain.doFilter(request, response);
        } finally {
            if (isMutation(request.getMethod())) {
                try {
                    audit.record(
                            request.getMethod() + " " + request.getRequestURI(),
                            "HTTP_RESOURCE", request.getRequestURI(),
                            response.getStatus() < 400 ? "SUCCESS" : "DENIED",
                            Map.of("status", response.getStatus())
                    );
                } catch (RuntimeException auditFailure) {
                    log.error("记录审计事件失败", auditFailure);
                }
            }
            TenantContext.clear();
        }
    }

    private RequestIdentity identity(HttpServletRequest request) {

        if (tokens != null && tokens.required()) return tokens.authenticate(request);
        String tenant = required(request, "X-Tenant-Id");
        String actor = required(request, "X-Actor-Id");
        String rolesHeader = required(request, "X-Roles");
        Set<EnterpriseRole> roles = Arrays.stream(rolesHeader.split(","))
                .map(String::strip).filter(value -> !value.isEmpty())
                .map(value -> EnterpriseRole.valueOf(value.toUpperCase()))
                .collect(Collectors.toUnmodifiableSet());
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("X-Roles不能为空");
        }
        String requestId = request.getHeader("X-Request-Id");
        return new RequestIdentity(
                UUID.fromString(tenant), actor, roles,
                requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId
        );
    }

    private String required(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少身份头：" + name);
        }
        return value;
    }

    private boolean isMutation(String method) {
        return Set.of("POST", "PUT", "PATCH", "DELETE").contains(method);
    }
}
