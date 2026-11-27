package com.supportforge.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("supportforge.slo")
public record SloProperties(
        @DefaultValue("0.99") double availabilityTarget,
        @DefaultValue("2000") long latencyTargetMs
) {
    public SloProperties {
        if (!Double.isFinite(availabilityTarget) || availabilityTarget <= 0
                || availabilityTarget > 1 || latencyTargetMs < 1) {
            throw new IllegalArgumentException("SLO配置不合法");
        }
    }
}
