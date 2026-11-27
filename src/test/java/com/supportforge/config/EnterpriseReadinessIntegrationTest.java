package com.supportforge.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "management.endpoint.health.probes.enabled=true",
        "management.endpoint.health.group.readiness.include=readinessState,postgres"
})
@AutoConfigureMockMvc
class EnterpriseReadinessIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AtomicBoolean databaseAvailable;

    @Test
    void databaseFailureChangesReadinessButNotLiveness() throws Exception {
        try {
            databaseAvailable.set(true);
            mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
            databaseAvailable.set(false);
            mvc.perform(get("/actuator/health/readiness"))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value("DOWN"));
            mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        } finally { databaseAvailable.set(true); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseProbe {
        @Bean AtomicBoolean databaseAvailable() { return new AtomicBoolean(true); }
        @Bean(name = "postgres")
        HealthIndicator postgres(AtomicBoolean databaseAvailable) {
            return () -> databaseAvailable.get() ? Health.up().build() : Health.down().build();
        }
    }
}
