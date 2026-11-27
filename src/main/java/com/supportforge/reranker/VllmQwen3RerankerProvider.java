package com.supportforge.reranker;

import com.supportforge.config.RerankerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** vLLM/Cohere兼容 /v1/rerank HTTP Provider。 */
@Component
@ConditionalOnProperty(
        prefix = "supportforge.reranker", name = "provider", havingValue = "vllm"
)
public class VllmQwen3RerankerProvider implements RerankerProvider {

    private final RerankerProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public VllmQwen3RerankerProvider(
            RerankerProperties properties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.timeout()).build();
    }

    @Override
    public RerankBatch rerank(String query, List<String> documents) {
        MockQwen3RerankerProvider.validate(query, documents);
        List<RerankScore> all = new ArrayList<>();
        for (int offset = 0; offset < documents.size(); offset += properties.batchSize()) {
            int end = Math.min(documents.size(), offset + properties.batchSize());
            all.addAll(requestBatch(query.strip(), documents.subList(offset, end), offset));
        }
        if (all.size() != documents.size()) {
            throw new RerankerProviderException("Reranker结果数量与候选数量不一致");
        }
        return new RerankBatch(properties.model(), properties.modelVersion(), all);
    }

    private List<RerankScore> requestBatch(
            String query,
            List<String> documents,
            int offset
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("query", query);
        body.put("documents", documents);
        body.put("top_n", documents.size());
        body.put("return_documents", false);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(properties.validatedEndpoint())
                .timeout(properties.timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body)
                ));
        if (!properties.apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + properties.apiKey());
        }
        HttpResponse<String> response;
        try {
            response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RerankerProviderException("Reranker请求被中断", exception);
        } catch (IOException exception) {
            throw new RerankerProviderException("Reranker网络请求失败", exception);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RerankerProviderException(
                    "Reranker返回HTTP " + response.statusCode()
            );
        }
        return decode(response.body(), documents.size(), offset);
    }

    private List<RerankScore> decode(String json, int expected, int offset) {
        Map<?, ?> root;
        try {
            root = objectMapper.readValue(json, Map.class);
        } catch (RuntimeException exception) {
            throw new RerankerProviderException("Reranker响应不是合法JSON", exception);
        }
        Object raw = root.get("results");
        if (!(raw instanceof List<?> results)) {
            throw new RerankerProviderException("Reranker响应缺少results");
        }
        List<RerankScore> decoded = new ArrayList<>();
        Set<Integer> indexes = new HashSet<>();
        for (Object item : results) {
            if (!(item instanceof Map<?, ?> map)
                    || !(map.get("index") instanceof Number index)
                    || !(map.get("relevance_score") instanceof Number score)) {
                throw new RerankerProviderException("Reranker结果字段非法");
            }
            int local = index.intValue();
            if (local < 0 || local >= expected || !indexes.add(local)) {
                throw new RerankerProviderException("Reranker结果序号越界或重复");
            }
            decoded.add(new RerankScore(offset + local, score.doubleValue()));
        }
        if (decoded.size() != expected) {
            throw new RerankerProviderException("Reranker批次结果不完整");
        }
        return decoded;
    }
}
