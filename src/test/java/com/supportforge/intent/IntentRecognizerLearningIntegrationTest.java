package com.supportforge.intent;

import com.supportforge.llm.LlmClient;
import com.supportforge.llm.LlmResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentRecognizerLearningIntegrationTest {

    @Test
    void shouldClearOldResultCacheAndUseLearnedCorrection() {
        AtomicInteger llmCalls = new AtomicInteger();
        IntentRecognizer recognizer = recognizer(llmCalls);

        IntentResult before = recognizer.recognize(
                "申请退款",
                "固定上下文"
        );
        boolean learned = recognizer.learn(
                "申请退款",
                IntentCategory.REFUND
        );
        IntentResult after = recognizer.recognize(
                "申请退款",
                "固定上下文"
        );

        assertEquals(IntentCategory.BILLING, before.intent());
        assertTrue(learned);
        assertEquals(IntentCategory.REFUND, after.intent());
        assertEquals(2, llmCalls.get());
        assertEquals(2, recognizer.cacheStats().misses());
        assertEquals(1, recognizer.cacheStats().size());
        assertEquals(1, recognizer.learnedTemplateCount());
    }

    @Test
    void shouldKeepCacheWhenCorrectionIsAnExactDuplicate() {
        AtomicInteger llmCalls = new AtomicInteger();
        IntentRecognizer recognizer = recognizer(llmCalls);

        assertTrue(recognizer.learn(
                "包裹卡在中转站",
                IntentCategory.LOGISTICS
        ));
        IntentResult first = recognizer.recognize(
                "包裹卡在中转站",
                "固定上下文"
        );
        assertFalse(recognizer.learn(
                "  包裹卡在中转站  ",
                IntentCategory.LOGISTICS
        ));
        IntentResult second = recognizer.recognize(
                "包裹卡在中转站",
                "固定上下文"
        );

        assertEquals(IntentCategory.LOGISTICS, first.intent());
        assertEquals(first, second);
        assertEquals(1, llmCalls.get());
        assertEquals(1, recognizer.cacheStats().hits());
        assertEquals(1, recognizer.cacheStats().misses());
    }

    @Test
    void shouldRejectLearningInLegacyCompatibilityMode() {
        IntentRecognizer legacy =
                new IntentRecognizer(new EntityExtractor());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> legacy.learn(
                        "申请退款",
                        IntentCategory.REFUND
                )
        );

        assertTrue(exception.getMessage().contains("兼容模式"));
    }

    private IntentRecognizer recognizer(
            AtomicInteger llmCalls
    ) {
        LlmClient client = request -> {
            llmCalls.incrementAndGet();
            return new LlmResponse(
                    "这不是意图分类JSON",
                    "test-model",
                    10,
                    5,
                    1.0,
                    true
            );
        };

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
                new IntentRecognitionCache(20, 10)
        );
    }
}
