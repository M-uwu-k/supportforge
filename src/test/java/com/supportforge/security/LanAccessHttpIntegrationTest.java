package com.supportforge.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"supportforge.features.multi-tenancy=true", "supportforge.auth.required=true",
        "management.prometheus.metrics.export.enabled=true",
        "management.defaults.metrics.export.enabled=true", "management.endpoint.health.probes.enabled=true",
        "management.endpoints.web.exposure.include=health,prometheus", "supportforge.intent.llm.enabled=false"})
@AutoConfigureMockMvc
class LanAccessHttpIntegrationTest {
    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void credentials(DynamicPropertyRegistry registry) {
        registry.add("supportforge.auth.credentials-json", () -> "[" + credential("test-agent", "AGENT", false)
                + "," + credential("test-admin", "ADMIN", false)
                + "," + credential("test-auditor", "AUDITOR", false)
                + "," + credential("test-monitor", "AUDITOR", true) + "]");
    }

    private static String credential(String token, String role, boolean metricsOnly) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
            return "{\"token_sha256\":\"" + hash + "\",\"tenant_id\":\"00000000-0000-0000-0000-000000000001\","
                    + "\"actor_id\":\"" + token + "\",\"roles\":[\"" + role + "\"],\"metrics_only\":" + metricsOnly + "}";
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    @Test
    void forgedHeadersCannotReplaceAnAccessToken() throws Exception {
        mvc.perform(get("/auth/me").header("X-Tenant-Id", "00000000-0000-0000-0000-000000000001")
                        .header("X-Actor-Id", "fake-admin").header("X-Roles", "ADMIN"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/me").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenIdentityOverridesBrowserTenantActorAndRoles() throws Exception {
        mvc.perform(get("/auth/me").header("Authorization", "Bearer test-agent")
                        .header("X-Tenant-Id", "00000000-0000-0000-0000-000000000002")
                        .header("X-Actor-Id", "fake-admin").header("X-Roles", "ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.actor_id").value("test-agent"))
                .andExpect(jsonPath("$.roles[0]").value("AGENT"))
                .andExpect(jsonPath("$.tenant_id").value("00000000-0000-0000-0000-000000000001"));
        mvc.perform(post("/intents/learn").header("Authorization", "Bearer test-agent")
                        .header("X-Roles", "ADMIN").contentType("application/json")
                        .content("{\"message\":\"cannot-poison\",\"correct_intent\":\"refund\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditorCannotMutateEndpointsWithoutExplicitRoleAnnotations() throws Exception {
        mvc.perform(delete("/conversations/test/messages").header("Authorization", "Bearer test-auditor"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/feedback").header("Authorization", "Bearer test-auditor")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void monitorTokenCanOnlyScrapeMetricsAndHealthRemainsAvailable() throws Exception {
        mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer test-monitor"))
                .andExpect(status().isOk());
        mvc.perform(get("/auth/me").header("Authorization", "Bearer test-monitor"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void knowledgeCacheDoesNotCrossRolesAndAclGrantRefreshesThePreviousEmptyResult() throws Exception {
        String query = "退款缓存权限回归" + java.util.UUID.randomUUID();
        String body = new tools.jackson.databind.ObjectMapper().writeValueAsString(
                java.util.Map.of("parameters", java.util.Map.of("query", query, "limit", 3)));
        mvc.perform(post("/tools/knowledge_search/call").header("Authorization", "Bearer test-admin")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isNotEmpty());
        mvc.perform(post("/tools/knowledge_search/call").header("Authorization", "Bearer test-agent")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(post("/tools/knowledge_search/call").header("Authorization", "Bearer test-agent")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cached").value(true))
                .andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(post("/access/acl").header("Authorization", "Bearer test-admin")
                        .contentType("application/json").content("""
                                {"resource_type":"knowledge_document","resource_id":"billing-refund",
                                 "principal_id":"test-agent","permission":"READ"}
                                """))
                .andExpect(status().isOk());
        mvc.perform(post("/tools/knowledge_search/call").header("Authorization", "Bearer test-agent")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.data[0].document.id").value("billing-refund"));
    }

    @Test
    void frameworkHttpErrorsKeepTheirStatusAndProtocolHeaders() throws Exception {
        mvc.perform(get("/unknown-review-route").header("Authorization", "Bearer test-agent"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        mvc.perform(post("/knowledge/search").header("Authorization", "Bearer test-admin"))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.status").value(405))
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")));
        mvc.perform(post("/chat").header("Authorization", "Bearer test-agent")
                        .contentType("text/plain").content("not JSON"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.status").value(415));
    }
}
