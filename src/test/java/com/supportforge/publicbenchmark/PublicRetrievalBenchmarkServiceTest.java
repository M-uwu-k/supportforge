package com.supportforge.publicbenchmark;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.supportforge.benchmark.RetrievalBenchmarkVariant;
import com.supportforge.config.EmbeddingProperties;
import com.supportforge.config.FeatureFlagProperties;
import com.supportforge.config.QdrantProperties;
import com.supportforge.config.RerankerProperties;
import com.supportforge.embedding.MockQwen3EmbeddingProvider;
import com.supportforge.reranker.RerankBatch;
import com.supportforge.reranker.RerankScore;
import com.supportforge.reranker.RerankerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class PublicRetrievalBenchmarkServiceTest {
    private HttpServer server;
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private PublicRetrievalBenchmarkService service;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        UUID pointB = UUID.randomUUID();
        UUID pointA = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        UUID documentA = UUID.randomUUID();
        UUID versionB = UUID.randomUUID();
        UUID versionA = UUID.randomUUID();
        server.createContext("/collections/public", exchange -> {
            try {
                String body = readBody(exchange);
                if (!body.isBlank()) requestBodies.add(body);
                String path = exchange.getRequestURI().getPath();
                if (exchange.getRequestMethod().equals("GET")) {
                    respond(exchange, 404, "{\"status\":\"not found\"}");
                } else if (path.endsWith("/points/count")) {
                    respond(exchange, 200, "{\"result\":{\"count\":2},\"status\":\"ok\"}");
                } else if (path.endsWith("/points/query")) {
                    respond(exchange, 200, """
                            {"result":{"points":[
                              {"id":"%s","score":0.9,"payload":{
                                "document_id":"%s","document_version_id":"%s",
                                "external_document_id":"doc-b","chunk_index":0,
                                "content":"次相关商品","content_sha256":"%s",
                                "category":"PUBLIC_ECOM_BENCHMARK","index_version":"public-v1"}},
                              {"id":"%s","score":0.8,"payload":{
                                "document_id":"%s","document_version_id":"%s",
                                "external_document_id":"doc-a","chunk_index":0,
                                "content":"最相关商品","content_sha256":"%s",
                                "category":"PUBLIC_ECOM_BENCHMARK","index_version":"public-v1"}}
                            ]},"status":"ok"}
                            """.formatted(
                            pointB, documentB, versionB, "a".repeat(64),
                            pointA, documentA, versionA, "b".repeat(64)
                    ));
                } else {
                    respond(exchange, 200, "{\"result\":true,\"status\":\"ok\"}");
                }
            } catch (Exception exception) {
                respond(exchange, 500, "{\"error\":\"test\"}");
            }
        });
        server.start();

        EmbeddingProperties embedding = new EmbeddingProperties(
                "mock", "embedding", "embedding-v1", "http://localhost", "",
                4, 10, Duration.ofSeconds(2), true, "query"
        );
        QdrantProperties qdrant = new QdrantProperties(
                "qdrant", "http://localhost:" + server.getAddress().getPort(),
                "", "production", "dense", "production-v1", 4, "Cosine",
                1, 1, 1, 64, Duration.ofSeconds(2)
        );
        RerankerProperties reranker = new RerankerProperties(
                "mock", "reranker", "reranker-v1", "http://localhost", "",
                32, 30, 0.0, Duration.ofSeconds(2), true
        );
        PublicBenchmarkProperties publicProperties = new PublicBenchmarkProperties(
                "dataset", "revision", "public", "public-v1", UUID.randomUUID(),
                100, 10, 60, 256, 5000
        );
        RerankerProvider rerankerProvider = (query, documents) -> {
            List<RerankScore> scores = java.util.stream.IntStream
                    .range(0, documents.size())
                    .mapToObj(index -> new RerankScore(index, 0.9 - index * 0.1))
                    .toList();
            return new RerankBatch("reranker", "reranker-v1", scores);
        };
        service = new PublicRetrievalBenchmarkService(
                publicProperties, new MockQwen3EmbeddingProvider(embedding),
                embedding, qdrant, rerankerProvider, reranker,
                new FeatureFlagProperties(false, true, false, false,
                        false, false, false),
                new ObjectMapper()
        );
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void importsPassagesIntoIsolatedCollectionAndReportsExactCount() {
        PublicBenchmarkImportResult result = service.importBatch(
                new PublicBenchmarkImportRequest(
                        "dataset", "revision",
                        List.of(new PublicBenchmarkPassage("p1", "商品正文"))
                )
        );

        assertThat(result.upserted()).isEqualTo(1);
        assertThat(result.indexedPoints()).isEqualTo(2);
        assertThat(requestBodies).anyMatch(body ->
                body.contains("\"external_document_id\":\"p1\"")
                        && body.contains("\"index_version\":\"public-v1\"")
        );
    }

    @Test
    void calculatesGradedMetricsForAllFourRealQueryShapes() {
        PublicBenchmarkReport report = service.evaluate(
                new PublicBenchmarkRunRequest(
                        "dataset", "revision", 10, 100, false,
                        List.of(new PublicBenchmarkQuery(
                                "q1", "商品", Map.of("doc-a", 3, "doc-b", 1)
                        ))
                )
        );

        assertThat(report.indexedPoints()).isEqualTo(2);
        assertThat(report.variants()).hasSize(4);
        assertThat(report.variants()).extracting(PublicBenchmarkVariantReport::variant)
                .containsExactly(RetrievalBenchmarkVariant.values());
        for (PublicBenchmarkVariantReport variant : report.variants()) {
            assertThat(variant.metrics().hitAtK()).isEqualTo(1.0);
            assertThat(variant.metrics().recallAtK()).isEqualTo(1.0);
            assertThat(variant.metrics().ndcgAtK()).isBetween(0.70, 0.72);
            assertThat(variant.metrics().candidateMisses()).isZero();
            assertThat(variant.metrics().rankingMisses()).isZero();
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

