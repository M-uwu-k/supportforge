package com.supportforge.embedding;

import com.supportforge.config.EmbeddingProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MockQwen3EmbeddingProviderTest {

    @Test
    void isDeterministicNormalizedAndDimensionSafe() {
        MockQwen3EmbeddingProvider provider =
                new MockQwen3EmbeddingProvider(properties(32));

        EmbeddingBatch first = provider.embedDocuments(
                List.of("退款需要订单号", "登录失败检查凭证")
        );
        EmbeddingBatch second = provider.embedDocuments(
                List.of("退款需要订单号", "登录失败检查凭证")
        );

        assertEquals(first, second);
        assertEquals(32, first.dimensions());
        assertEquals(2, first.vectors().size());
        assertNotEquals(
                first.vectors().get(0).values(),
                first.vectors().get(1).values()
        );
        double norm = first.vectors().getFirst().values()
                .stream()
                .mapToDouble(value -> value * value)
                .sum();
        assertEquals(1.0, norm, 0.00001);
    }

    @Test
    void queryAndSameDocumentRemainComparableInMockMode() {
        MockQwen3EmbeddingProvider provider =
                new MockQwen3EmbeddingProvider(properties(32));

        assertEquals(
                provider.embedQuery("退款流程")
                        .vectors().getFirst().values(),
                provider.embedDocuments(List.of("退款流程"))
                        .vectors().getFirst().values()
        );
    }

    private EmbeddingProperties properties(int dimensions) {
        return new EmbeddingProperties(
                "mock",
                "Qwen/Qwen3-Embedding-0.6B",
                "test-v1",
                "http://localhost:8000/v1/embeddings",
                "",
                dimensions,
                10,
                Duration.ofSeconds(2),
                true,
                "Retrieve customer support passages"
        );
    }
}
