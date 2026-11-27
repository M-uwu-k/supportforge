package com.supportforge.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("supportforge.resilience")
public record ResilienceProperties(
        @DefaultValue("2s") Duration dependencyTimeout,
        @DefaultValue("3") int circuitFailureThreshold,
        @DefaultValue("10s") Duration circuitOpenDuration,
        @DefaultValue("50") int maximumConcurrentCalls,
        @DefaultValue("100") int rateLimitRequests,
        @DefaultValue("1m") Duration rateLimitWindow
) {
    public ResilienceProperties {
        if (dependencyTimeout == null || dependencyTimeout.isZero() || dependencyTimeout.isNegative()
                || circuitFailureThreshold < 1 || circuitOpenDuration == null
                || circuitOpenDuration.isZero() || circuitOpenDuration.isNegative()
                || maximumConcurrentCalls < 1 || rateLimitRequests < 1
                || rateLimitWindow == null || rateLimitWindow.isZero() || rateLimitWindow.isNegative()) {
            throw new IllegalArgumentException("韧性配置不合法");
        }
    }
}
