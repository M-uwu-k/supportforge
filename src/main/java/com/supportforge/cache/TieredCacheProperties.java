package com.supportforge.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("supportforge.cache")
public record TieredCacheProperties(
        @DefaultValue("10000") int maximumEntries,
        @DefaultValue("30s") Duration l1Ttl,
        @DefaultValue("10m") Duration l2Ttl,
        @DefaultValue("redis://localhost:6379") String valkeyUri,
        @DefaultValue("supportforge:v1") String namespace
) {
    public TieredCacheProperties {
        if (maximumEntries < 1 || l1Ttl == null || l1Ttl.isNegative() || l1Ttl.isZero()
                || l2Ttl == null || l2Ttl.isNegative() || l2Ttl.isZero()
                || valkeyUri == null || valkeyUri.isBlank()
                || namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("分层缓存配置不合法");
        }
    }
}
