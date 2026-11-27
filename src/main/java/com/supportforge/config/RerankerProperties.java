package com.supportforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

/** Qwen3 Reranker服务和精排策略配置。 */
@ConfigurationProperties(prefix = "supportforge.reranker")
public record RerankerProperties(
        String provider,
        String model,
        String modelVersion,
        String endpoint,
        String apiKey,
        int batchSize,
        int candidateLimit,
        double minimumScore,
        Duration timeout,
        boolean failOpen
) {

    public RerankerProperties {
        provider = normalize(provider, "mock").toLowerCase(Locale.ROOT);
        model = normalize(model, "Qwen/Qwen3-Reranker-0.6B");
        modelVersion = normalize(modelVersion, "qwen3-reranker-v1");
        endpoint = normalize(endpoint, "http://localhost:8001/v1/rerank");
        apiKey = apiKey == null ? "" : apiKey;
        batchSize = batchSize == 0 ? 32 : batchSize;
        candidateLimit = candidateLimit == 0 ? 30 : candidateLimit;
        timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
        if (!provider.equals("mock") && !provider.equals("vllm")) {
            throw new IllegalArgumentException("Reranker provider只支持mock或vllm");
        }
        if (batchSize < 1 || batchSize > 256 || candidateLimit < 1
                || candidateLimit > 1000) {
            throw new IllegalArgumentException("Reranker批大小或候选数非法");
        }
        if (!Double.isFinite(minimumScore)
                || minimumScore < 0.0 || minimumScore > 1.0) {
            throw new IllegalArgumentException("Reranker最低分必须在0到1之间");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Reranker超时必须为正数");
        }
        validatedEndpoint(endpoint);
    }

    public URI validatedEndpoint() {
        return validatedEndpoint(endpoint);
    }

    private static URI validatedEndpoint(String value) {
        URI uri = URI.create(value);
        if (!("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("Reranker endpoint必须是完整HTTP地址");
        }
        return uri;
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
