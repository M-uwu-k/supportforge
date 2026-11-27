package com.supportforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

/** Qwen3 Embedding Provider 配置。默认Mock保证local/test不依赖GPU或外部API。 */
@ConfigurationProperties(prefix = "supportforge.embedding")
public record EmbeddingProperties(
        String provider,
        String model,
        String modelVersion,
        String endpoint,
        String apiKey,
        int dimensions,
        int batchSize,
        Duration timeout,
        boolean normalize,
        String queryInstruction
) {

    public EmbeddingProperties {
        provider = normalize(provider, "mock").toLowerCase(Locale.ROOT);
        model = normalize(model, "Qwen/Qwen3-Embedding-0.6B");
        modelVersion = normalize(modelVersion, "qwen3-embedding-v1");
        endpoint = normalize(endpoint, "http://localhost:8000/v1/embeddings");
        apiKey = apiKey == null ? "" : apiKey;
        dimensions = dimensions == 0 ? 1024 : dimensions;
        batchSize = batchSize == 0 ? 10 : batchSize;
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        queryInstruction = normalize(
                queryInstruction,
                "Given a customer support search query, retrieve relevant passages that answer the query"
        );

        if (!provider.equals("mock")
                && !provider.equals("openai-compatible")) {
            throw new IllegalArgumentException(
                    "Embedding provider只支持mock或openai-compatible："
                            + provider
            );
        }
        if (dimensions < 1 || dimensions > 8192) {
            throw new IllegalArgumentException(
                    "Embedding维度必须在1到8192之间"
            );
        }
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException(
                    "Embedding批大小必须在1到100之间"
            );
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "Embedding超时必须为正数"
            );
        }
    }

    public URI validatedEndpoint() {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Embedding endpoint不是合法URI：" + endpoint,
                    exception
            );
        }
        if (!("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalStateException(
                    "Embedding endpoint必须是完整HTTP/HTTPS地址："
                            + endpoint
            );
        }
        return uri;
    }

    private static String normalize(
            String value,
            String defaultValue
    ) {
        return value == null || value.isBlank()
                ? defaultValue
                : value.strip();
    }
}
