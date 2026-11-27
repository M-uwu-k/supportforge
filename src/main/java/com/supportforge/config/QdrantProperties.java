package com.supportforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/** Qdrant Collection、named vector和写入策略配置。 */
@ConfigurationProperties(prefix = "supportforge.qdrant")
public record QdrantProperties(
        String provider,
        String endpoint,
        String apiKey,
        String collection,
        String denseVectorName,
        String indexVersion,
        int dimensions,
        String distance,
        int shardNumber,
        int replicationFactor,
        int writeConsistencyFactor,
        int upsertBatchSize,
        Duration timeout
) {

    private static final Pattern SAFE_NAME =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,254}");

    public QdrantProperties {
        provider = normalize(provider, "memory").toLowerCase(Locale.ROOT);
        endpoint = normalize(endpoint, "http://localhost:6333");
        apiKey = apiKey == null ? "" : apiKey;
        collection = normalize(collection, "supportforge_knowledge_v1");
        denseVectorName = normalize(denseVectorName, "qwen3_dense");
        indexVersion = normalize(indexVersion, "knowledge-qwen3-v1");
        dimensions = dimensions == 0 ? 1024 : dimensions;
        distance = normalize(distance, "Cosine");
        shardNumber = shardNumber == 0 ? 1 : shardNumber;
        replicationFactor = replicationFactor == 0 ? 1 : replicationFactor;
        writeConsistencyFactor = writeConsistencyFactor == 0
                ? 1 : writeConsistencyFactor;
        upsertBatchSize = upsertBatchSize == 0 ? 64 : upsertBatchSize;
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;

        if (!provider.equals("memory") && !provider.equals("qdrant")) {
            throw new IllegalArgumentException(
                    "Qdrant provider只支持memory或qdrant：" + provider
            );
        }
        validateName(collection, "Collection名称");
        validateName(denseVectorName, "dense vector名称");
        validateName(indexVersion, "索引版本");
        if (dimensions < 1 || dimensions > 65_536) {
            throw new IllegalArgumentException("Qdrant维度必须在1到65536之间");
        }
        if (!distance.equals("Cosine")
                && !distance.equals("Dot")
                && !distance.equals("Euclid")
                && !distance.equals("Manhattan")) {
            throw new IllegalArgumentException("不支持的Qdrant距离函数：" + distance);
        }
        if (shardNumber < 1 || replicationFactor < 1
                || writeConsistencyFactor < 1
                || writeConsistencyFactor > replicationFactor) {
            throw new IllegalArgumentException(
                    "Qdrant分片/副本/写一致性配置非法"
            );
        }
        if (upsertBatchSize < 1 || upsertBatchSize > 1_000) {
            throw new IllegalArgumentException("Qdrant写入批大小必须在1到1000之间");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Qdrant超时必须为正数");
        }
    }

    public URI validatedEndpoint() {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Qdrant endpoint不是合法URI：" + endpoint, exception);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalStateException(
                    "Qdrant endpoint必须是完整HTTP/HTTPS地址：" + endpoint
            );
        }
        return uri;
    }

    public String endpointWithoutTrailingSlash() {
        String value = validatedEndpoint().toString();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static void validateName(String value, String label) {
        if (!SAFE_NAME.matcher(value).matches()
                || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException(label + "不安全：" + value);
        }
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
