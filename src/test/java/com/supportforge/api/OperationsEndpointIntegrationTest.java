package com.supportforge.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@SpringBootTest(properties = {
        "supportforge.llm.provider=mock",
        "supportforge.knowledge.storage-path=target/test-data/${random.uuid}/knowledge.json",


        "management.endpoints.web.exposure.include=health,prometheus",
        "management.endpoint.health.probes.enabled=true",

        "management.defaults.metrics.export.enabled=true",
        "management.prometheus.metrics.export.enabled=true"
})
@AutoConfigureMockMvc
class OperationsEndpointIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldExposeHealthLivenessAndReadiness() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status")
                        .value("UP"));

        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status")
                        .value("UP"));

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status")
                        .value("UP"));
    }

    @Test
    void shouldExposePrometheusMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString(
                                "supportforge_chat_requests_total"
                        )
                ));
    }
}
