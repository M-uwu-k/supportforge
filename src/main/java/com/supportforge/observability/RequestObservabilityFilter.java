package com.supportforge.observability;

import com.supportforge.security.RequestIdentity;
import com.supportforge.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 25)
public class RequestObservabilityFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestObservabilityFilter.class);
    private final MeterRegistry meters;
    private final SloMetricsService slo;

    public RequestObservabilityFilter(MeterRegistry meters, SloMetricsService slo) {
        this.meters = meters;
        this.slo = slo;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain
    ) throws ServletException, IOException {
        long started = System.nanoTime();
        RequestIdentity identity = TenantContext.current().orElse(null);
        String requestId = identity == null
                ? headerOrGenerated(request, "X-Request-Id") : identity.requestId();
        MDC.put("request_id", requestId);
        if (identity != null) {
            MDC.put("tenant_id", identity.tenantId().toString());
            MDC.put("actor_id", identity.actorId());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            long elapsedNanos = System.nanoTime() - started;
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
            String statusClass = response.getStatus() / 100 + "xx";
            Timer.builder("supportforge.http.server.duration")
                    .description("SupportForge HTTP request duration")
                    .tag("method", request.getMethod())
                    .tag("status", statusClass)
                    .publishPercentileHistogram()
                    .register(meters)
                    .record(elapsedNanos, TimeUnit.NANOSECONDS);
            meters.counter(
                    "supportforge.http.server.requests",
                    "method", request.getMethod(), "status", statusClass
            ).increment();
            slo.record(response.getStatus(), elapsedMs);
            log.atInfo()
                    .addKeyValue("event", "http_request")
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("path", request.getRequestURI())
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("latency_ms", elapsedMs)
                    .log("request_completed");
            MDC.clear();
        }
    }

    private String headerOrGenerated(HttpServletRequest request, String header) {
        String value = request.getHeader(header);
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }
}
