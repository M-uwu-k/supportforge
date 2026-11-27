package com.supportforge.resilience;

import com.supportforge.security.RequestIdentity;
import com.supportforge.security.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
@ConditionalOnProperty(
        prefix = "supportforge.features", name = "multi-tenancy", havingValue = "true"
)
public class ApiRateLimitFilter extends OncePerRequestFilter {
    private final RateLimitPort rateLimit;
    private final ResilienceProperties properties;

    public ApiRateLimitFilter(RateLimitPort rateLimit, ResilienceProperties properties) {
        this.rateLimit = rateLimit;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain
    ) throws ServletException, IOException {
        RequestIdentity identity = TenantContext.current().orElse(null);
        if (identity == null || request.getRequestURI().startsWith("/actuator/health")) {
            chain.doFilter(request, response);
            return;
        }
        String key = identity.tenantId() + ":" + identity.actorId();
        boolean allowed;
        try {
            allowed = rateLimit.tryAcquire(
                    key, properties.rateLimitRequests(), properties.rateLimitWindow()
            );
        } catch (RuntimeException unavailable) {

            allowed = true;
        }
        if (!allowed) {
            response.setHeader("Retry-After", Long.toString(properties.rateLimitWindow().toSeconds()));
            response.sendError(429, "请求过于频繁");
            return;
        }
        chain.doFilter(request, response);
    }
}
