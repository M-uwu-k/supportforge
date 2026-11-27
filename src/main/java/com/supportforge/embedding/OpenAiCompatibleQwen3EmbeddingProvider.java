package com.supportforge.embedding;

import com.supportforge.config.EmbeddingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** OpenAI-compatible /v1/embeddings Qwen3实现。协议错误会抛异常，不返回空向量。 */
@Component
@ConditionalOnProperty(
        prefix = "supportforge.embedding",
        name = "provider",
        havingValue = "openai-compatible"
)
public class OpenAiCompatibleQwen3EmbeddingProvider
        implements EmbeddingProvider {

    private final EmbeddingProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleQwen3EmbeddingProvider(
            EmbeddingProperties properties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.timeout())
                .build();
        properties.validatedEndpoint();
    }

    @Override
    public EmbeddingBatch embed(
            List<String> texts,
            EmbeddingPurpose purpose
    ) {
        List<String> formatted = EmbeddingSupport.validateAndFormat(
                texts,
                purpose,
                properties
        );
        List<EmbeddingVector> combined = new ArrayList<>();
        int totalTokens = 0;
        String responseModel = properties.model();

        for (int offset = 0;
             offset < formatted.size();
             offset += properties.batchSize()) {
            int end = Math.min(
                    formatted.size(),
                    offset + properties.batchSize()
            );
            ParsedResponse parsed = requestBatch(
                    formatted.subList(offset, end)
            );
            responseModel = parsed.model();
            totalTokens += parsed.inputTokens();
            for (EmbeddingVector local : parsed.vectors()) {
                combined.add(new EmbeddingVector(
                        offset + local.index(),
                        local.values()
                ));
            }
        }
        combined.sort(Comparator.comparingInt(EmbeddingVector::index));
        return new EmbeddingBatch(
                responseModel,
                properties.modelVersion(),
                properties.dimensions(),
                combined,
                totalTokens
        );
    }

    private ParsedResponse requestBatch(List<String> inputs) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", properties.model());
        requestBody.put("input", inputs);
        requestBody.put("encoding_format", "float");
        requestBody.put("dimensions", properties.dimensions());

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(properties.validatedEndpoint())
                .timeout(properties.timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(requestBody)
                ));
        if (!properties.apiKey().isBlank()) {
            builder.header(
                    "Authorization",
                    "Bearer " + properties.apiKey()
            );
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new EmbeddingProviderException(
                    "Embedding请求被中断",
                    exception
            );
        } catch (IOException exception) {
            throw new EmbeddingProviderException(
                    "Embedding网络请求失败",
                    exception
            );
        }
        if (response.statusCode() < 200
                || response.statusCode() >= 300) {
            throw new EmbeddingProviderException(
                    "Embedding服务返回HTTP " + response.statusCode()
            );
        }

        Map<?, ?> body;
        try {
            body = objectMapper.readValue(response.body(), Map.class);
        } catch (RuntimeException exception) {
            throw new EmbeddingProviderException(
                    "Embedding响应不是合法JSON",
                    exception
            );
        }
        return parseResponse(body, inputs.size());
    }

    private ParsedResponse parseResponse(
            Map<?, ?> body,
            int expectedCount
    ) {
        Object rawData = body.get("data");
        if (!(rawData instanceof List<?> data)
                || data.size() != expectedCount) {
            throw new EmbeddingProviderException(
                    "Embedding响应数量与输入不一致"
            );
        }

        List<EmbeddingVector> vectors = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (Object item : data) {
            if (!(item instanceof Map<?, ?> map)
                    || !(map.get("index") instanceof Number number)
                    || !(map.get("embedding") instanceof List<?> rawVector)) {
                throw new EmbeddingProviderException(
                        "Embedding响应data结构非法"
                );
            }
            int index = number.intValue();
            if (index < 0 || index >= expectedCount
                    || !seen.add(index)) {
                throw new EmbeddingProviderException(
                        "Embedding响应index重复或越界"
                );
            }
            if (rawVector.size() != properties.dimensions()) {
                throw new EmbeddingProviderException(
                        "Embedding维度漂移：期望"
                                + properties.dimensions()
                                + "，实际"
                                + rawVector.size()
                );
            }
            List<Float> values = new ArrayList<>(rawVector.size());
            for (Object value : rawVector) {
                if (!(value instanceof Number numeric)) {
                    throw new EmbeddingProviderException(
                            "Embedding向量包含非数字元素"
                    );
                }
                values.add(numeric.floatValue());
            }
            vectors.add(new EmbeddingVector(
                    index,
                    EmbeddingSupport.normalize(
                            values,
                            properties.normalize()
                    )
            ));
        }
        vectors.sort(Comparator.comparingInt(EmbeddingVector::index));
        int inputTokens = 0;
        Object rawUsage = body.get("usage");
        if (rawUsage instanceof Map<?, ?> usage
                && usage.get("total_tokens") instanceof Number number) {
            inputTokens = Math.max(0, number.intValue());
        }
        return new ParsedResponse(
                Objects.toString(
                        body.get("model"),
                        properties.model()
                ),
                List.copyOf(vectors),
                inputTokens
        );
    }

    private record ParsedResponse(
            String model,
            List<EmbeddingVector> vectors,
            int inputTokens
    ) {
    }
}
