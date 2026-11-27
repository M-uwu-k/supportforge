package com.supportforge.reranker;

import com.supportforge.config.RerankerProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockQwen3RerankerProviderTest {

    @Test
    void deterministicallyScoresMoreRelevantChineseTextHigher() {
        MockQwen3RerankerProvider provider =
                new MockQwen3RerankerProvider(properties());

        RerankBatch result = provider.rerank(
                "退款多久到账",
                List.of("退款三个工作日到账", "登录密码重置方法")
        );

        assertEquals(2, result.scores().size());
        assertTrue(result.scores().get(0).score()
                > result.scores().get(1).score());
        assertEquals("reranker-v1", result.modelVersion());
    }

    private RerankerProperties properties() {
        return new RerankerProperties(
                "mock", "model", "reranker-v1", "http://localhost", "",
                2, 10, 0.0, Duration.ofSeconds(1), true
        );
    }
}
