package com.supportforge.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.supportforge.cache.TieredCacheService;
import com.supportforge.vector.InMemoryVectorIndexAdapter;
import com.supportforge.vector.KnowledgeVectorPoint;
import com.supportforge.trace.RetrievalTracePort;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实HTTP协议Adapter + 实际检索/准入/生成/校验主链；仅远端模型用本机协议服务替代，绝不调用收费API。 */
@SpringBootTest(properties = {
        "supportforge.features.qdrant-retrieval=true",
        "supportforge.features.grounding-harness=true",
        "supportforge.features.multi-tenancy=true",
        "supportforge.qdrant.provider=memory",
        "supportforge.qdrant.dimensions=4",
        "supportforge.qdrant.index-version=integration-v1",
        "supportforge.embedding.provider=openai-compatible",
        "supportforge.embedding.model=test-embedding",
        "supportforge.embedding.dimensions=4",
        "supportforge.reranker.provider=vllm",
        "supportforge.reranker.model=test-rerank",
        "supportforge.reranker.fail-open=true",
        "supportforge.llm.provider=openai-compatible",
        "supportforge.llm.model=test-llm",
        "supportforge.llm.chat-max-tokens=8192",
        "supportforge.llm.api-key=local-test-key",
        "supportforge.intent.llm.enabled=false"
})
@AutoConfigureMockMvc
class EnterpriseChatHttpIntegrationTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final HttpServer SERVER = server();
    private static final AtomicInteger EMBEDDINGS = new AtomicInteger();
    private static final AtomicInteger RERANKS = new AtomicInteger();
    private static final AtomicInteger GENERATIONS = new AtomicInteger();
    private static final AtomicInteger GENERATION_BUDGET = new AtomicInteger();
    private static final AtomicReference<String> MODE = new AtomicReference<>("success");
    private static final AtomicReference<String> PROMPT = new AtomicReference<>("");

    @Autowired MockMvc mvc;
    @Autowired InMemoryVectorIndexAdapter vectors;
    @Autowired RetrievalTracePort traces;
    @Autowired ObjectMapper mapper;
    @Autowired TieredCacheService cache;
    @Autowired com.supportforge.security.ResourceAccessService access;
    @Autowired com.supportforge.knowledge.KnowledgeBase knowledge;

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry properties) {
        String base = "http://127.0.0.1:" + SERVER.getAddress().getPort();
        properties.add("supportforge.embedding.endpoint", () -> base + "/embeddings");
        properties.add("supportforge.reranker.endpoint", () -> base + "/rerank");
        properties.add("supportforge.llm.chat-url", () -> base + "/chat/completions");
    }

    @BeforeEach
    void prepare() {
        MODE.set("success");
        EMBEDDINGS.set(0); RERANKS.set(0); GENERATIONS.set(0);
        vectors.upsert(List.of(
                point(TENANT, "enterprise-refund", "退款到账通常需要三个工作日，请提交订单号并等待人工审核。"),
                point(OTHER, "private-other", "退款到账属于其他租户的私有资料，其他租户客服审核。")
        ));
    }

    @AfterAll
    static void stop() { SERVER.stop(0); }

    @Test
    void passesTheRealSourceTitleToTheModelAndReturnsItInCitations() throws Exception {
        knowledge.upsert(new com.supportforge.knowledge.KnowledgeDocument(
                "enterprise-refund", "企业退款处理说明", com.supportforge.knowledge.KnowledgeCategory.BILLING,
                "退款到账通常需要三个工作日，请提交订单号并等待人工审核。", List.of("退款")));
        chat(TENANT, "退款多久到账")
                .andExpect(jsonPath("$.grounding_status").value("grounded"))
                .andExpect(jsonPath("$.knowledge_sources[0].title").value("企业退款处理说明"))
                .andExpect(jsonPath("$.knowledge_sources[0].matched_keywords[0]").value("退款"));
        assertThat(PROMPT.get()).contains("来源标题：企业退款处理说明");
        assertThat(PROMPT.get()).contains("检索关键词（仅作主题标签）：退款");
    }

    @Test
    void chatActuallyCallsEmbeddingVectorSearchRerankerAndLlmAndReturnsCitedEvidence() throws Exception {
        String body = chat(TENANT, "退款多久到账")
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.grounding_status").value("grounded"))
                .andExpect(jsonPath("$.knowledge_sources[0].id").value("enterprise-refund"))
                .andExpect(jsonPath("$.evidence[0].external_document_id").value("enterprise-refund"))
                .andExpect(jsonPath("$.retrieval_trace_id").isNotEmpty())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(EMBEDDINGS.get()).isEqualTo(1);
        assertThat(RERANKS.get()).isEqualTo(1);
        assertThat(GENERATIONS.get()).isEqualTo(1);
        assertThat(PROMPT.get()).contains("三个工作日").doesNotContain("其他租户的私有资料");
        assertThat(GENERATION_BUDGET.get()).isEqualTo(8192);
        UUID traceId = UUID.fromString(mapper.readTree(body).get("retrieval_trace_id").asText());
        assertThat(traces.find(TENANT, traceId)).isPresent();
        assertThat(traces.find(OTHER, traceId)).isEmpty();
    }

    @Test
    void emptyTenantEvidenceRefusesWithoutCallingRerankerOrLlm() throws Exception {
        chat(UUID.randomUUID(), "退款多久到账")
                .andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.knowledge_used").value(false))
                .andExpect(jsonPath("$.grounding_status").value("evidence_refused"));
        assertThat(EMBEDDINGS.get()).isEqualTo(1);
        assertThat(RERANKS.get()).isZero();
        assertThat(GENERATIONS.get()).isZero();
    }

    @Test
    void failedRerankerIsNotReportedAsSuccessfulGrounding() throws Exception {
        MODE.set("rerank-failed");
        chat(TENANT, "退款多久到账")
                .andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.grounding_status").value("evidence_refused"));
        assertThat(RERANKS.get()).isPositive();
        assertThat(GENERATIONS.get()).isZero();
    }

    @Test
    void rejectsInventedNumbersAndUnknownCitationsInsteadOfReturningTheirSources() throws Exception {
        MODE.set("invalid-answer");
        chat(TENANT, "退款多久到账")
                .andExpect(jsonPath("$.grounding_status").value("answer_rejected"))
                .andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.evidence").isEmpty())
                .andExpect(jsonPath("$.knowledge_sources").isEmpty());
        assertThat(GENERATIONS.get()).isEqualTo(1);
    }

    @Test
    void greetingUsesRulesWithoutUnnecessaryPaidModelCalls() throws Exception {
        chat(TENANT, "你好").andExpect(jsonPath("$.grounding_status").value("not_required"));
        assertThat(EMBEDDINGS.get()).isZero();
        assertThat(RERANKS.get()).isZero();
        assertThat(GENERATIONS.get()).isZero();
    }

    @Test
    void doesNotDropTheOrderIdRequiredByBusinessRules() throws Exception {
        chat(TENANT, "订单号A12345退款多久到账")
                .andExpect(jsonPath("$.grounding_status").value("answer_rejected"))
                .andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.knowledge_sources").isEmpty());
    }

    @Test
    void agentWithoutDocumentPermissionCannotSendItToRerankerOrLlm() throws Exception {
        mvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", TENANT.toString())
                        .header("X-Actor-Id", "ungranted-agent").header("X-Roles", "AGENT")
                        .content("{\"message\":\"退款多久到账\",\"user_id\":\"acl-check\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.grounded").value(false));
        assertThat(RERANKS.get()).isZero();
        assertThat(GENERATIONS.get()).isZero();
    }

    @Test
    void explicitDocumentGrantAllowsAnAgentToUseTheActualRagChain() throws Exception {
        access.grant("knowledge_document", "enterprise-refund", "granted-agent",
                com.supportforge.security.AclPermission.READ);
        mvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", TENANT.toString())
                        .header("X-Actor-Id", "granted-agent").header("X-Roles", "AGENT")
                        .content("{\"message\":\"退款多久到账\",\"user_id\":\"acl-check\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.grounded").value(true));
        assertThat(RERANKS.get()).isEqualTo(1);
        assertThat(GENERATIONS.get()).isEqualTo(1);
    }

    @Test
    void readOnlyAuditorCannotUseTheChatMutationEndpoint() throws Exception {
        mvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", TENANT.toString())
                        .header("X-Actor-Id", "auditor").header("X-Roles", "AUDITOR")
                        .content("{\"message\":\"退款多久到账\",\"user_id\":\"acl-check\"}"))
                .andExpect(status().isForbidden());
        assertThat(EMBEDDINGS.get()).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions chat(UUID tenant, String message) throws Exception {
        return mvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", tenant.toString())
                        .header("X-Actor-Id", "integration-agent").header("X-Roles", "ADMIN")
                        .content(mapper.writeValueAsString(java.util.Map.of(
                                "message", message, "user_id", "test-customer",
                                "conversation_id", UUID.randomUUID().toString()))))
                .andExpect(status().isOk());
    }

    @Test
    void nonAdministratorsCannotPoisonLearningSamplesOrLaunchPaidEvaluation() throws Exception {
        for (String role : List.of("AGENT", "AUDITOR")) {
            mvc.perform(post("/intents/learn").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Tenant-Id", TENANT.toString()).header("X-Actor-Id", "readonly")
                            .header("X-Roles", role)
                            .content("{\"message\":\"unauthorized-correction\",\"correct_intent\":\"refund\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/eval/judge").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Tenant-Id", TENANT.toString()).header("X-Actor-Id", "readonly")
                            .header("X-Roles", role)
                            .content("{\"question\":\"退款问题\",\"response\":\"回答\"}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/eval/history")
                        .header("X-Tenant-Id", TENANT.toString()).header("X-Actor-Id", "auditor")
                        .header("X-Roles", "AUDITOR"))
                .andExpect(status().isOk());
        assertThat(GENERATIONS.get()).isZero();
    }

    private static KnowledgeVectorPoint point(UUID tenant, String externalId, String content) {
        return new KnowledgeVectorPoint(UUID.nameUUIDFromBytes((tenant + externalId).getBytes(StandardCharsets.UTF_8)),
                tenant, UUID.randomUUID(), UUID.randomUUID(), externalId, 0, content, "a".repeat(64),
                "BILLING", "integration-v1", "test-embedding", "v1", List.of(1f, 0f, 0f, 0f), true);
    }

    private static HttpServer server() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", EnterpriseChatHttpIntegrationTest::respond);
            server.start();
            return server;
        } catch (IOException exception) { throw new ExceptionInInitializerError(exception); }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        String body;
        int status = 200;
        if (path.equals("/embeddings")) {
            EMBEDDINGS.incrementAndGet();
            body = "{\"model\":\"test-embedding\",\"data\":[{\"index\":0,\"embedding\":[1,0,0,0]}]}";
        } else if (path.equals("/rerank")) {
            RERANKS.incrementAndGet();
            if (MODE.get().equals("rerank-failed")) { status = 503; body = "{}"; }
            else { body = "{\"results\":[{\"index\":0,\"relevance_score\":0.95}]}"; }
        } else {
            var payload = new ObjectMapper().readTree(request);
            if (payload.get("messages").get(0).get("content").asText().contains("你是客服知识检索的查询改写器")) {
                String query = payload.get("messages").get(1).get("content").asText();
                body = new ObjectMapper().writeValueAsString(java.util.Map.of("choices", List.of(java.util.Map.of(
                        "message", java.util.Map.of("content", new ObjectMapper().writeValueAsString(java.util.Map.of("query", query)))))));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
                return;
            }
            GENERATIONS.incrementAndGet(); PROMPT.set(request);
            GENERATION_BUDGET.set(new ObjectMapper().readTree(request).get("max_tokens").asInt());
            String answer = MODE.get().equals("invalid-answer")
                    ? "退款999天到账。[E99]" : "退款通常需要三个工作日到账，请提交订单号并等待人工审核。[E1]";
            body = new ObjectMapper().writeValueAsString(java.util.Map.of(
                    "model", "test-llm", "choices", List.of(java.util.Map.of(
                            "message", java.util.Map.of("content", answer)))));
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
