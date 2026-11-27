package com.supportforge.intent;

import com.supportforge.llm.LlmClient;
import com.supportforge.llm.LlmResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;


class IntentRecognizerCacheIntegrationTest {

    @Test
    void shouldReturnCachedResultWithoutCallingLlmAgain() {

        AtomicInteger calls = new AtomicInteger();

        LlmClient client = request -> {
            calls.incrementAndGet();

            return response(IntentCategory.REFUND, 0.92);
        };
        IntentRecognitionCache cache =
                new IntentRecognitionCache(10, 5);
        IntentRecognizer recognizer = recognizer(client, cache);


        IntentResult first = recognizer.recognize(
                "我要申请退款",
                "订单A12345"
        );

        IntentResult second = recognizer.recognize(
                "我要申请退款",
                "订单A12345"
        );

        assertEquals(1, calls.get());
        assertEquals(first, second);
        assertEquals(1, recognizer.cacheStats().hits());
        assertEquals(1, recognizer.cacheStats().misses());
        assertEquals(1, recognizer.cacheStats().size());
    }

    @Test
    void shouldRecognizeAgainWhenContextChanges() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = request -> {
            calls.incrementAndGet();
            return response(IntentCategory.QUERY, 0.90);
        };
        IntentRecognitionCache cache =
                new IntentRecognitionCache(10, 5);
        IntentRecognizer recognizer = recognizer(client, cache);


        recognizer.recognize("还是那个问题", "退款上下文");

        recognizer.recognize("还是那个问题", "登录上下文");


        assertEquals(2, calls.get());
        assertEquals(0, recognizer.cacheStats().hits());
        assertEquals(2, recognizer.cacheStats().misses());
        assertEquals(2, recognizer.cacheStats().size());
    }

    @Test
    void shouldCallLlmAgainAfterCacheIsCleared() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = request -> {
            calls.incrementAndGet();
            return response(IntentCategory.TECHNICAL_LOGIN, 0.95);
        };
        IntentRecognitionCache cache =
                new IntentRecognitionCache(10, 5);
        IntentRecognizer recognizer = recognizer(client, cache);

        recognizer.recognize("登录一直报401", "同一上下文");
        recognizer.recognize("登录一直报401", "同一上下文");
        recognizer.clearCache();
        recognizer.recognize("登录一直报401", "同一上下文");

        assertEquals(2, calls.get());
        assertEquals(1, recognizer.cacheStats().hits());
        assertEquals(2, recognizer.cacheStats().misses());
        assertEquals(1, recognizer.cacheStats().size());
    }


    private IntentRecognizer recognizer(
            LlmClient client,
            IntentRecognitionCache cache
    ) {
        return new IntentRecognizer(
                new EntityExtractor(),
                new LlmIntentRecognizer(
                        client,
                        new ObjectMapper(),
                        true
                ),
                TemplateEmbeddingIntentRecognizer.defaults(),
                new PatternIntentRecognizer(),
                IntentFusion.defaults(),
                cache
        );
    }


    private LlmResponse response(
            IntentCategory intent,
            double confidence
    ) {
        return new LlmResponse(
                "{\"intent\":\""
                        + intent.value()
                        + "\",\"confidence\":"
                        + confidence
                        + ",\"reasoning\":\"测试分类\"}",
                "test-model",
                10,
                5,
                1.0,
                true
        );
    }
}
