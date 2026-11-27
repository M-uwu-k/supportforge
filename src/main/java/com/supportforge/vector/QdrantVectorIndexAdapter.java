package com.supportforge.vector;

import com.supportforge.config.EmbeddingProperties;
import com.supportforge.config.FeatureFlagProperties;
import com.supportforge.config.QdrantProperties;
import com.supportforge.retrieval.HybridRetrievalPort;
import com.supportforge.retrieval.HybridSearchHit;
import com.supportforge.retrieval.HybridSearchRequest;
import com.supportforge.retrieval.RetrievalMode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Qdrant REST Adapter：建Collection、校验schema、建Payload索引并幂等upsert。 */
@Component
@ConditionalOnProperty(
        prefix = "supportforge.qdrant",
        name = "provider",
        havingValue = "qdrant"
)
public class QdrantVectorIndexAdapter
        implements VectorIndexPort, HybridRetrievalPort {

    private static final Map<String, String> PAYLOAD_INDEXES = Map.ofEntries(
            Map.entry("tenant_id", "uuid"),
            Map.entry("document_id", "uuid"),
            Map.entry("document_version_id", "uuid"),
            Map.entry("external_document_id", "keyword"),
            Map.entry("category", "keyword"),
            Map.entry("index_version", "keyword"),
            Map.entry("embedding_model", "keyword"),
            Map.entry("embedding_version", "keyword"),
            Map.entry("content_sha256", "keyword"),
            Map.entry("chunk_index", "integer"),
            Map.entry("active", "bool")
    );

    private final QdrantProperties properties;
    private final EmbeddingProperties embeddingProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private volatile boolean ready;

    public QdrantVectorIndexAdapter(
            QdrantProperties properties,
            EmbeddingProperties embeddingProperties,
            FeatureFlagProperties featureFlags,
            ObjectMapper objectMapper
    ) {
        if (!featureFlags.qdrantRetrieval()) {
            throw new IllegalStateException(
                    "Qdrant provider=qdrant时必须开启"
                            + "supportforge.features.qdrant-retrieval"
            );
        }
        if (properties.dimensions() != embeddingProperties.dimensions()) {
            throw new IllegalStateException(
                    "Qdrant与Embedding维度不一致："
                            + properties.dimensions() + " != "
                            + embeddingProperties.dimensions()
            );
        }
        this.properties = properties;
        this.embeddingProperties = embeddingProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .build();
        properties.validatedEndpoint();
    }

    @Override
    public synchronized QdrantCollectionSpec ensureReady() {
        HttpResult response = request(
                "GET",
                collectionPath(),
                null,
                true
        );
        Map<String, String> payloadSchema;
        if (response.statusCode() == 404) {
            createCollection();
            payloadSchema = Map.of();
        } else {
            payloadSchema = validateExistingCollection(response.body());
        }
        ensurePayloadIndexes(payloadSchema);
        ready = true;
        return expectedSpec();
    }

    @Override
    public void upsert(List<KnowledgeVectorPoint> points) {
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("待写入向量Point不能为空");
        }
        if (!ready) {
            ensureReady();
        }
        for (KnowledgeVectorPoint point : points) {
            validatePoint(point);
        }
        for (int offset = 0;
             offset < points.size();
             offset += properties.upsertBatchSize()) {
            int end = Math.min(
                    points.size(),
                    offset + properties.upsertBatchSize()
            );
            upsertBatch(points.subList(offset, end));
        }
    }

    @Override
    public List<HybridSearchHit> search(HybridSearchRequest search) {
        if (!ready) {
            ensureReady();
        }
        if (search.denseVector().size() != properties.dimensions()) {
            throw new IllegalArgumentException("查询向量维度与Qdrant不一致");
        }
        if (!search.indexVersion().equals(properties.indexVersion())) {
            throw new IllegalArgumentException("查询索引版本与Qdrant不一致");
        }
        Map<String, Object> filter = searchFilter(search);
        Map<String, Object> body = new LinkedHashMap<>();
        if (search.mode() == RetrievalMode.DENSE) {
            body.put("query", search.denseVector());
            body.put("using", properties.denseVectorName());
        } else if (search.mode() == RetrievalMode.BM25) {
            body.put("query", Map.of("text", search.queryText()));
            body.put("using", "content");
        } else {
            Map<String, Object> dense = new LinkedHashMap<>();
            dense.put("query", search.denseVector());
            dense.put("using", properties.denseVectorName());
            dense.put("filter", filter);
            dense.put("limit", search.candidateLimit());

            Map<String, Object> lexical = new LinkedHashMap<>();
            lexical.put("query", Map.of("text", search.queryText()));
            lexical.put("using", "content");
            lexical.put("filter", filter);
            lexical.put("limit", search.candidateLimit());

            body.put("prefetch", List.of(dense, lexical));
            body.put("query", Map.of("rrf", Map.of("k", search.rrfK())));
        }
        body.put("filter", filter);
        body.put("limit", search.limit());
        body.put("with_payload", true);
        body.put("with_vector", false);
        HttpResult response = request(
                "POST",
                collectionPath() + "/points/query",
                body,
                false
        );
        return decodeHits(response.body());
    }

    /** Returns the exact number of active points for one tenant and index version. */
    public long countPoints(UUID tenantId, String indexVersion) {
        if (tenantId == null || indexVersion == null || indexVersion.isBlank()) {
            throw new IllegalArgumentException("计数租户和索引版本不能为空");
        }
        if (!ready) {
            ensureReady();
        }
        Map<String, Object> filter = Map.of("must", List.of(
                match("tenant_id", tenantId.toString()),
                match("index_version", indexVersion.strip()),
                match("active", true)
        ));
        HttpResult response = request(
                "POST",
                collectionPath() + "/points/count",
                Map.of("filter", filter, "exact", true),
                false
        );
        Map<?, ?> root = parseObject(response.body(), "Count响应");
        Map<?, ?> result = requireMap(root.get("result"), "result");
        Object count = result.get("count");
        if (!(count instanceof Number number) || number.longValue() < 0) {
            throw new IllegalStateException("Qdrant Count响应缺少合法result.count");
        }
        return number.longValue();
    }

    private void createCollection() {
        Map<String, Object> dense = new LinkedHashMap<>();
        dense.put("size", properties.dimensions());
        dense.put("distance", properties.distance());

        Map<String, Object> vectors = new LinkedHashMap<>();
        vectors.put(properties.denseVectorName(), dense);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("supportforge_index_version", properties.indexVersion());
        metadata.put("embedding_model", embeddingProperties.model());
        metadata.put("embedding_version", embeddingProperties.modelVersion());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vectors", vectors);
        body.put("shard_number", properties.shardNumber());
        body.put("replication_factor", properties.replicationFactor());
        body.put(
                "write_consistency_factor",
                properties.writeConsistencyFactor()
        );
        body.put("metadata", metadata);
        request("PUT", collectionPath(), body, false);
    }

    private Map<String, String> validateExistingCollection(String rawBody) {
        Map<?, ?> root = parseObject(rawBody, "Collection响应");
        Map<?, ?> result = requireMap(root.get("result"), "result");
        Map<?, ?> config = requireMap(result.get("config"), "result.config");
        Map<?, ?> params = requireMap(config.get("params"), "result.config.params");
        Map<?, ?> vectors = requireMap(params.get("vectors"), "vectors");
        Map<?, ?> dense = requireMap(
                vectors.get(properties.denseVectorName()),
                "named dense vector " + properties.denseVectorName()
        );
        Object size = dense.get("size");
        Object distance = dense.get("distance");
        if (!(size instanceof Number number)
                || number.intValue() != properties.dimensions()) {
            throw new IllegalStateException(
                    "Qdrant Collection维度漂移：期望"
                            + properties.dimensions() + "，实际" + size
            );
        }
        if (!properties.distance().equals(String.valueOf(distance))) {
            throw new IllegalStateException(
                    "Qdrant Collection距离函数漂移：期望"
                            + properties.distance() + "，实际" + distance
            );
        }

        Map<String, String> schema = new LinkedHashMap<>();
        Object rawSchema = result.get("payload_schema");
        if (rawSchema == null) {
            return schema;
        }
        Map<?, ?> payloadSchema = requireMap(rawSchema, "payload_schema");
        for (Map.Entry<?, ?> entry : payloadSchema.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            Map<?, ?> field = requireMap(
                    entry.getValue(),
                    "payload_schema." + entry.getKey()
            );
            if ("content".equals(entry.getKey().toString())) {
                validateContentIndex(field);
            }
            schema.put(
                    entry.getKey().toString(),
                    String.valueOf(field.get("data_type"))
            );
        }
        return schema;
    }

    private void ensurePayloadIndexes(Map<String, String> existing) {
        for (Map.Entry<String, String> expected : PAYLOAD_INDEXES.entrySet()) {
            String actual = existing.get(expected.getKey());
            if (actual == null) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("field_name", expected.getKey());
                body.put("field_schema", expected.getValue());
                request(
                        "PUT",
                        collectionPath() + "/index?wait=true",
                        body,
                        false
                );
            } else if (!actual.equals(expected.getValue())) {
                throw new IllegalStateException(
                        "Qdrant Payload索引类型漂移："
                                + expected.getKey() + "期望"
                                + expected.getValue() + "，实际" + actual
                );
            }
        }
        String contentType = existing.get("content");
        if (contentType == null) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "text");
            schema.put("tokenizer", "multilingual");
            schema.put("lowercase", true);
            schema.put("scoring", Map.of("type", "bm25"));
            request(
                    "PUT",
                    collectionPath() + "/index?wait=true",
                    Map.of("field_name", "content", "field_schema", schema),
                    false
            );
        } else if (!"text".equals(contentType)) {
            throw new IllegalStateException(
                    "Qdrant content索引必须是带BM25评分的text类型"
            );
        }
    }

    private void validateContentIndex(Map<?, ?> field) {
        Map<?, ?> params = requireMap(
                field.get("params"),
                "payload_schema.content.params"
        );
        Map<?, ?> scoring = requireMap(
                params.get("scoring"),
                "payload_schema.content.params.scoring"
        );
        if (!"text".equals(String.valueOf(params.get("type")))
                || !"multilingual".equals(String.valueOf(params.get("tokenizer")))
                || !Boolean.TRUE.equals(params.get("lowercase"))
                || !"bm25".equals(String.valueOf(scoring.get("type")))) {
            throw new IllegalStateException(
                    "Qdrant content全文索引配置漂移，期望multilingual/lowercase/BM25"
            );
        }
    }

    private void upsertBatch(List<KnowledgeVectorPoint> points) {
        List<Map<String, Object>> encoded = new ArrayList<>();
        for (KnowledgeVectorPoint point : points) {
            Map<String, Object> vectors = new LinkedHashMap<>();
            vectors.put(properties.denseVectorName(), point.denseVector());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tenant_id", point.tenantId().toString());
            payload.put("document_id", point.documentId().toString());
            payload.put(
                    "document_version_id",
                    point.documentVersionId().toString()
            );
            payload.put("external_document_id", point.externalDocumentId());
            payload.put("chunk_index", point.chunkIndex());
            payload.put("content", point.content());
            payload.put("content_sha256", point.contentSha256());
            payload.put("category", point.category());
            payload.put("index_version", point.indexVersion());
            payload.put("embedding_model", point.embeddingModel());
            payload.put("embedding_version", point.embeddingVersion());
            payload.put("active", point.active());

            Map<String, Object> encodedPoint = new LinkedHashMap<>();
            encodedPoint.put("id", point.pointId().toString());
            encodedPoint.put("vector", vectors);
            encodedPoint.put("payload", payload);
            encoded.add(encodedPoint);
        }
        request(
                "PUT",
                collectionPath() + "/points?wait=true",
                Map.of("points", encoded),
                false
        );
    }

    private void validatePoint(KnowledgeVectorPoint point) {
        if (point.denseVector().size() != properties.dimensions()) {
            throw new IllegalArgumentException(
                    "向量Point维度与Qdrant Collection不一致"
            );
        }
        if (!point.indexVersion().equals(properties.indexVersion())) {
            throw new IllegalArgumentException(
                    "向量Point索引版本与Qdrant Collection不一致"
            );
        }
        if (!point.embeddingModel().equals(embeddingProperties.model())
                || !point.embeddingVersion().equals(
                embeddingProperties.modelVersion())) {
            throw new IllegalArgumentException(
                    "向量Point模型身份与当前Embedding配置不一致"
            );
        }
    }

    private Map<String, Object> searchFilter(HybridSearchRequest search) {
        List<Map<String, Object>> must = new ArrayList<>();
        must.add(match("tenant_id", search.tenantId().toString()));
        must.add(match("index_version", search.indexVersion()));
        must.add(match("active", true));
        if (search.categories().size() == 1) {
            must.add(match("category", search.categories().iterator().next()));
        } else if (!search.categories().isEmpty()) {
            must.add(Map.of(
                    "key", "category",
                    "match", Map.of("any", search.categories().stream().sorted().toList())
            ));
        }
        if (search.excludedPointIds().isEmpty()) return Map.of("must", must);

        return Map.of("must", must, "must_not", List.of(Map.of(
                "has_id", search.excludedPointIds().stream().map(UUID::toString).sorted().toList()
        )));
    }

    private static Map<String, Object> match(String key, Object value) {
        return Map.of("key", key, "match", Map.of("value", value));
    }

    private List<HybridSearchHit> decodeHits(String rawBody) {
        Map<?, ?> root = parseObject(rawBody, "Query响应");
        Map<?, ?> result = requireMap(root.get("result"), "result");
        Object rawPoints = result.get("points");
        if (!(rawPoints instanceof List<?> points)) {
            throw new IllegalStateException("Qdrant Query响应缺少result.points");
        }
        List<HybridSearchHit> hits = new ArrayList<>();
        for (Object rawPoint : points) {
            Map<?, ?> point = requireMap(rawPoint, "result.points[]");
            Map<?, ?> payload = requireMap(point.get("payload"), "point.payload");
            hits.add(new HybridSearchHit(
                    parseUuid(point.get("id"), "point.id"),
                    parseUuid(payload.get("document_id"), "document_id"),
                    parseUuid(payload.get("document_version_id"), "document_version_id"),
                    requiredText(payload, "external_document_id"),
                    requiredInteger(payload, "chunk_index"),
                    requiredText(payload, "content"),
                    requiredText(payload, "content_sha256"),
                    requiredText(payload, "category"),
                    requiredText(payload, "index_version"),
                    requiredDouble(point, "score")
            ));
        }
        return List.copyOf(hits);
    }

    private static UUID parseUuid(Object value, String path) {
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Qdrant响应UUID非法：" + path, exception);
        }
    }

    private static String requiredText(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException("Qdrant响应缺少文本字段：" + key);
        }
        return value.toString();
    }

    private static int requiredInteger(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Qdrant响应缺少数字字段：" + key);
        }
        return number.intValue();
    }

    private static double requiredDouble(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Qdrant响应缺少分数字段：" + key);
        }
        return number.doubleValue();
    }

    private HttpResult request(
            String method,
            String path,
            Object body,
            boolean allowNotFound
    ) {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(
                objectMapper.writeValueAsString(body)
        );
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(properties.endpointWithoutTrailingSlash() + path))
                .timeout(properties.timeout())
                .header("Content-Type", "application/json")
                .method(method, publisher);
        if (!properties.apiKey().isBlank()) {
            builder.header("api-key", properties.apiKey());
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant请求被中断", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Qdrant网络请求失败", exception);
        }
        if (allowNotFound && response.statusCode() == 404) {
            return new HttpResult(response.statusCode(), response.body());
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Qdrant返回HTTP " + response.statusCode() + "："
                            + abbreviate(response.body())
            );
        }
        return new HttpResult(response.statusCode(), response.body());
    }

    private QdrantCollectionSpec expectedSpec() {
        return new QdrantCollectionSpec(
                properties.collection(),
                properties.denseVectorName(),
                properties.dimensions(),
                properties.distance(),
                properties.indexVersion()
        );
    }

    private String collectionPath() {
        return "/collections/" + properties.collection();
    }

    private Map<?, ?> parseObject(String json, String label) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(label + "不是合法JSON", exception);
        }
    }

    private static Map<?, ?> requireMap(Object value, String path) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("Qdrant响应缺少对象：" + path);
        }
        return map;
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private record HttpResult(int statusCode, String body) {
    }
}
