package com.supportforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "supportforge.features")
public record FeatureFlagProperties(

        boolean postgresPersistence,

        boolean qdrantRetrieval,

        boolean objectStorage,

        boolean kafkaIndexing,

        boolean distributedCache,

        boolean multiTenancy,

        boolean groundingHarness
) {


    public boolean anyEnterpriseFeatureEnabled() {
        return postgresPersistence
                || qdrantRetrieval
                || objectStorage
                || kafkaIndexing
                || distributedCache
                || multiTenancy
                || groundingHarness;
    }
}

