package com.supportforge.vector;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.supportforge.config.EmbeddingProperties;
import com.supportforge.config.FeatureFlagProperties;
import com.supportforge.config.QdrantProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QdrantVectorIndexAdapterTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void createsNamedVectorPayloadIndexesAndUpsertsPoint()
            throws Exception {
        List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        startServer(exchange -> {
            requests.add(new CapturedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(),
                    readBody(exchange)
            ));
            apiKey.set(exchange.getRequestHeaders().getFirst("api-key"));
            if (exchange.getRequestMethod().equals("GET")) {
                respond(exchange, 404, "{\"status\":\"not found\"}");
            } else {
                respond(exchange, 200, "{\"result\":true,\"status\":\"ok\"}");
            }
        });

        QdrantVectorIndexAdapter adapter = adapter(4);
        QdrantCollectionSpec spec = adapter.ensureReady();
        adapter.upsert(List.of(
                InMemoryVectorIndexAdapterTest.point("index-v1", 4)
        ));

        assertEquals(4, spec.dimensions());
        assertEquals("test-key", apiKey.get());
        assertTrue(requests.stream().anyMatch(request ->
                request.method().equals("PUT")
                        && request.path().equals("/collections/knowledge")
                        && request.body().contains("\"dense\"")
                        && request.body().contains("\"size\":4")));
        assertEquals(12, requests.stream().filter(request ->
                request.path().startsWith("/collections/knowledge/index"))
                .count());
        assertTrue(requests.stream().anyMatch(request ->
                request.path().startsWith("/collections/knowledge/index")
                        && request.body().contains("\"field_name\":\"content\"")
                        && request.body().contains("\"tokenizer\":\"multilingual\"")
                        && request.body().contains("\"type\":\"bm25\"")));
        assertTrue(requests.stream().anyMatch(request ->
                request.path().startsWith("/collections/knowledge/points")
                        && request.body().contains("\"index_version\":\"index-v1\"")
                        && request.body().contains("\"tenant_id\"")));
    }

    @Test
    void rejectsExistingCollectionDimensionDrift() throws Exception {
        startServer(exchange -> respond(exchange, 200, """
                {
                  "result": {
                    "config": {
                      "params": {
                        "vectors": {
                          "dense": {"size": 8, "distance": "Cosine"}
                        }
                      }
                    },
                    "payload_schema": {}
                  },
                  "status": "ok"
                }
                """));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> adapter(4).ensureReady()
        );
        assertTrue(exception.getMessage().contains("维度漂移"));
    }

    @Test
    void refusesQdrantProviderWhenFeatureFlagIsOff() throws Exception {
        startServer(exchange -> respond(exchange, 200, "{}"));

        assertThrows(
                IllegalStateException.class,
                () -> new QdrantVectorIndexAdapter(
                        properties(4),
                        embedding(4),
                        new FeatureFlagProperties(
                                false, false, false, false,
                                false, false, false
                        ),
                        new ObjectMapper()
                )
        );
    }

    @Test
    void sendsFilteredDenseBm25RrfQueryAndDecodesStronglyTypedHits()
            throws Exception {
        List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
        UUID pointId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        startServer(exchange -> {
            String body = readBody(exchange);
            requests.add(new CapturedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(), body
            ));
            if (exchange.getRequestMethod().equals("GET")) {
                respond(exchange, 404, "{\"status\":\"not found\"}");
            } else if (exchange.getRequestMethod().equals("POST")) {
                respond(exchange, 200, """
                        {"result":{"points":[{
                          "id":"%s","score":0.0327,"payload":{
                            "document_id":"%s",
                            "document_version_id":"%s",
                            "external_document_id":"refund-policy",
                            "chunk_index":2,
                            "content":"退款三个工作日到账",
                            "content_sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            "category":"billing",
                            "index_version":"index-v1"
                          }
                        }]},"status":"ok"}
                        """.formatted(pointId, documentId, versionId));
            } else {
                respond(exchange, 200, "{\"result\":true,\"status\":\"ok\"}");
            }
        });
        UUID tenant = UUID.randomUUID();

        List<com.supportforge.retrieval.HybridSearchHit> hits =
                adapter(4).search(new com.supportforge.retrieval.HybridSearchRequest(
                        tenant, "退款多久到账", List.of(1f, 0f, 0f, 0f),
                        Set.of("billing", "general"), "index-v1", 20, 5, 60
                ));

        assertEquals(1, hits.size());
        assertEquals(pointId, hits.getFirst().pointId());
        assertEquals("退款三个工作日到账", hits.getFirst().content());
        CapturedRequest query = requests.stream()
                .filter(request -> request.method().equals("POST"))
                .findFirst().orElseThrow();
        assertTrue(query.path().endsWith("/points/query"));
        assertTrue(query.body().contains("\"using\":\"dense\""));
        assertTrue(query.body().contains("\"using\":\"content\""));
        assertTrue(query.body().contains("\"rrf\":{\"k\":60}"));
        assertTrue(query.body().contains(tenant.toString()));
        assertTrue(query.body().contains("\"any\":[\"billing\",\"general\"]"));
        assertTrue(query.body().contains("\"active\""));
    }

    private QdrantVectorIndexAdapter adapter(int dimensions) {
        return new QdrantVectorIndexAdapter(
                properties(dimensions),
                embedding(dimensions),
                new FeatureFlagProperties(
                        false, true, false, false,
                        false, false, false
                ),
                new ObjectMapper()
        );
    }

    @Test
    void excludesAlreadyCheckedPointsInDenseLexicalAndFinalQueries() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        startServer(exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                respond(exchange, 404, "{\"status\":\"not found\"}");
            } else if (exchange.getRequestMethod().equals("POST")) {
                body.set(readBody(exchange));
                respond(exchange, 200, "{\"result\":{\"points\":[]},\"status\":\"ok\"}");
            } else {
                respond(exchange, 200, "{\"result\":true,\"status\":\"ok\"}");
            }
        });
        UUID excluded = UUID.randomUUID();
        adapter(4).search(new com.supportforge.retrieval.HybridSearchRequest(UUID.randomUUID(),
                "query", List.of(1f, 0f, 0f, 0f), Set.of(), "index-v1", 20, 5, 60, Set.of(excluded)));

        var json = new ObjectMapper().readTree(body.get());
        assertEquals(excluded.toString(), json.at("/filter/must_not/0/has_id/0").asText());
        assertEquals(json.get("filter"), json.at("/prefetch/0/filter"));
        assertEquals(json.get("filter"), json.at("/prefetch/1/filter"));
    }

    private QdrantProperties properties(int dimensions) {
        return new QdrantProperties(
                "qdrant",
                "http://localhost:" + server.getAddress().getPort(),
                "test-key", "knowledge", "dense", "index-v1",
                dimensions, "Cosine", 1, 1, 1, 10,
                Duration.ofSeconds(3)
        );
    }

    private EmbeddingProperties embedding(int dimensions) {
        return new EmbeddingProperties(
                "mock", "model", "model-v1",
                "http://localhost:8000/v1/embeddings", "",
                dimensions, 10, Duration.ofSeconds(3), true,
                "instruction"
        );
    }

    private void startServer(ThrowingHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/collections/knowledge", exchange -> {
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
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record CapturedRequest(String method, String path, String body) {
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
