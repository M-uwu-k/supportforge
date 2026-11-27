package com.supportforge.embedding;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.supportforge.config.EmbeddingProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleQwen3EmbeddingProviderTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsQwenInstructionAndRestoresResponseOrder()
            throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(readBody(exchange));
            authorization.set(exchange.getRequestHeaders()
                    .getFirst("Authorization"));
            respond(exchange, 200, """
                    {
                      "data": [
                        {"index": 1, "embedding": [0, 1, 0, 0]},
                        {"index": 0, "embedding": [1, 0, 0, 0]}
                      ],
                      "model": "qwen3-test-server",
                      "usage": {"total_tokens": 17}
                    }
                    """);
        });

        EmbeddingBatch result = provider(4).embed(
                List.of("退款怎么办", "登录失败"),
                EmbeddingPurpose.QUERY
        );

        assertEquals("Bearer test-key", authorization.get());
        assertTrue(requestBody.get().contains("Instruct:"));
        assertTrue(requestBody.get().contains("Query:退款怎么办"));
        assertTrue(requestBody.get().contains("\"dimensions\":4"));
        assertEquals(List.of(1.0f, 0.0f, 0.0f, 0.0f),
                result.vectors().get(0).values());
        assertEquals(List.of(0.0f, 1.0f, 0.0f, 0.0f),
                result.vectors().get(1).values());
        assertEquals(17, result.inputTokens());
        assertEquals("qwen3-test-server", result.model());
    }

    @Test
    void rejectsDimensionDrift() throws Exception {
        startServer(exchange -> respond(exchange, 200, """
                {
                  "data": [
                    {"index": 0, "embedding": [1, 0]}
                  ],
                  "model": "broken"
                }
                """));

        EmbeddingProviderException exception = assertThrows(
                EmbeddingProviderException.class,
                () -> provider(4).embedDocuments(List.of("正文"))
        );
        assertTrue(exception.getMessage().contains("维度漂移"));
    }

    private OpenAiCompatibleQwen3EmbeddingProvider provider(
            int dimensions
    ) {
        return new OpenAiCompatibleQwen3EmbeddingProvider(
                new EmbeddingProperties(
                        "openai-compatible",
                        "Qwen/Qwen3-Embedding-0.6B",
                        "test-v1",
                        "http://localhost:" + server.getAddress().getPort()
                                + "/v1/embeddings",
                        "test-key",
                        dimensions,
                        2,
                        Duration.ofSeconds(3),
                        true,
                        "Retrieve relevant support passages"
                ),
                new ObjectMapper()
        );
    }

    private void startServer(
            ThrowingHandler handler
    ) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception exception) {
                respond(exchange, 500, "{\"error\":\"test\"}");
            }
        });
        server.start();
    }

    private static String readBody(HttpExchange exchange)
            throws IOException {
        return new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8
        );
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            String body
    ) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json"
        );
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
