package com.supportforge.reranker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.supportforge.config.RerankerProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VllmQwen3RerankerProviderTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void batchesRequestsAndRestoresGlobalIndexes() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> bodies = new CopyOnWriteArrayList<>();
        start(exchange -> {
            bodies.add(readBody(exchange));
            assertEquals("Bearer secret",
                    exchange.getRequestHeaders().getFirst("Authorization"));
            int call = calls.getAndIncrement();
            respond(exchange, 200, call == 0
                    ? "{\"results\":[{\"index\":1,\"relevance_score\":0.2},{\"index\":0,\"relevance_score\":0.9}]}"
                    : "{\"results\":[{\"index\":0,\"relevance_score\":0.7}]}");
        });
        VllmQwen3RerankerProvider provider = new VllmQwen3RerankerProvider(
                properties(2), new ObjectMapper()
        );

        RerankBatch result = provider.rerank(
                "query", List.of("one", "two", "three")
        );

        assertEquals(2, calls.get());
        assertEquals(List.of(1, 0, 2), result.scores().stream()
                .map(RerankScore::index).toList());
        assertTrue(bodies.getFirst().contains("\"top_n\":2"));
        assertTrue(bodies.getFirst().contains("Qwen/Qwen3-Reranker-0.6B"));
    }

    @Test
    void rejectsDuplicateOrIncompleteIndexes() throws Exception {
        start(exchange -> respond(exchange, 200,
                "{\"results\":[{\"index\":0,\"relevance_score\":0.5}]}"));
        VllmQwen3RerankerProvider provider = new VllmQwen3RerankerProvider(
                properties(10), new ObjectMapper()
        );
        assertThrows(RerankerProviderException.class,
                () -> provider.rerank("query", List.of("one", "two")));
    }

    private RerankerProperties properties(int batchSize) {
        return new RerankerProperties(
                "vllm", "Qwen/Qwen3-Reranker-0.6B", "v1",
                "http://localhost:" + server.getAddress().getPort() + "/v1/rerank",
                "secret", batchSize, 30, 0.0,
                Duration.ofSeconds(2), true
        );
    }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/rerank", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception exception) {
                respond(exchange, 500, "{}");
            }
        });
        server.start();
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
