package com.supportforge.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import com.supportforge.config.LlmGenerationProperties;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleLlmClientTest {
    @Test
    void defaultCompatibilityDoesNotSendProviderSpecificThinkingField() throws Exception {
        assertThat(requestBody("").containsKey("thinking")).isFalse();
    }

    @Test
    void explicitlyDisabledThinkingIsSentToTheActualHttpEndpoint() throws Exception {
        Map<?, ?> body = requestBody("disabled");
        assertThat(body.get("thinking")).isEqualTo(Map.of("type", "disabled"));
        assertThat(body.get("max_tokens")).isEqualTo(8192);
    }

    @Test
    void truncatedNonemptyAnswerFailsAndRetainsReportedUsage() throws Exception {
        LlmResponse result = response("""
                {"choices":[{"finish_reason":"length","message":{"content":"半截回答"}}],
                 "usage":{"prompt_tokens":12,"completion_tokens":4096}}
                """, 0, Duration.ofSeconds(5));
        assertThat(result.success()).isFalse();
        assertThat(result.content()).contains("token上限");
        assertThat(result.inputTokens()).isEqualTo(12);
        assertThat(result.outputTokens()).isEqualTo(4096);
    }

    @Test
    void structuredContentIsNotConvertedToAnApparentlySuccessfulTextAnswer() throws Exception {
        assertThat(response("{\"choices\":[{\"message\":{\"content\":{\"unexpected\":\"object\"}}}]}",
                0, Duration.ofSeconds(5)).success()).isFalse();
    }

    @Test
    void slowEndpointIsBoundedByConfiguredResponseTimeout() throws Exception {
        long started = System.nanoTime();
        LlmResponse result = response("{\"choices\":[{\"message\":{\"content\":\"late\"}}]}",
                400, Duration.ofMillis(80));
        assertThat(result.success()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
    }

    private static LlmResponse response(String reply, int delayMillis, Duration readTimeout) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try { Thread.sleep(delayMillis); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            } catch (java.io.IOException clientTimedOut) { exchange.close(); }
        });
        server.start();
        try {
            return new OpenAiCompatibleLlmClient(RestClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/chat", "", "test", "",
                    new LlmGenerationProperties(4096, 2048, 1024, 2048, Duration.ofSeconds(2), readTimeout))
                    .generate(new LlmRequest("system", "question", "", 0, 4096));
        } finally { server.stop(0); }
    }

    @Test
    void invalidThinkingModeFailsBeforeAnyRemoteRequest() {
        assertThatThrownBy(() -> new OpenAiCompatibleLlmClient(RestClient.builder(),
                "http://127.0.0.1:1/unused", "", "test", "false"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<?, ?> requestBody(String thinkingMode) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<Map<?, ?>> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            captured.set(mapper.readValue(exchange.getRequestBody().readAllBytes(), Map.class));
            byte[] response = """
                    {"model":"test","choices":[{"message":{"content":"ok"}}],
                     "usage":{"prompt_tokens":4,"completion_tokens":1}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            LlmResponse response = new OpenAiCompatibleLlmClient(RestClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions",
                    "test-key", "test", thinkingMode).generate(new LlmRequest("system", "user", "", 0, 8192));
            assertThat(response.success()).isTrue();
            assertThat(response.content()).isEqualTo("ok");
            return captured.get();
        } finally {
            server.stop(0);
        }
    }
}
