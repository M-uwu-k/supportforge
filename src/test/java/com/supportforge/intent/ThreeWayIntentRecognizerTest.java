package com.supportforge.intent;

import com.supportforge.llm.LlmClient;
import com.supportforge.llm.LlmRequest;
import com.supportforge.llm.LlmResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreeWayIntentRecognizerTest {

    @Test
    void shouldForwardContextFuseThreeSourcesAndKeepEntities() {
        AtomicReference<LlmRequest> captured = new AtomicReference<>();
        LlmClient client = request -> {
            captured.set(request);
            return response(
                    "{\"intent\":\"refund\","
                            + "\"confidence\":0.90,"
                            + "\"reasoning\":\"用户明确申请退款\"}",
                    true
            );
        };
        IntentRecognizer recognizer = fullRecognizer(client);

        IntentResult result = recognizer.recognize(
                "订单号A12345，我要退款99元",
                "上一轮用户询问了该订单的扣款情况"
        );

        assertEquals(IntentCategory.REFUND, result.intent());
        assertEquals("billing", result.intentGroup());
        assertTrue(result.confidence() >= 0.63);
        assertEquals("上一轮用户询问了该订单的扣款情况",
                captured.get().context());
        assertEquals("订单号A12345，我要退款99元",
                captured.get().userMessage());
        assertTrue(result.sourceScores().containsKey("llm"));
        assertTrue(result.sourceScores().containsKey("embedding"));
        assertTrue(result.sourceScores().containsKey("pattern"));
        assertEquals(0.90, result.sourceScores().get("llm"), 0.000001);
        assertEquals("A12345", result.entities().get("order_id").getFirst());
        assertEquals("99元", result.entities().get("amount").getFirst());
        assertFalse(result.reasoning().isBlank());
    }

    @Test
    void shouldFallBackToExactEmbeddingWhenLlmOutputIsInvalid() {
        IntentRecognizer recognizer = fullRecognizer(
                request -> response("这不是分类JSON", true)
        );

        IntentResult result = recognizer.recognize(
                "登录一直报401",
                "历史上下文不影响本地降级分支"
        );

        assertEquals(IntentCategory.TECHNICAL_LOGIN, result.intent());
        assertEquals("technical", result.intentGroup());
        assertEquals(0.0, result.sourceScores().get("llm"), 0.000001);
        assertEquals(1.0, result.sourceScores().get("embedding"), 0.000001);
        assertTrue(result.reasoning().contains("LLM"));
    }

    @Test
    void shouldRetainUrgencyAndEntitiesAfterFusion() {
        IntentRecognizer recognizer = fullRecognizer(
                request -> response(
                        "{\"intent\":\"technical_login\","
                                + "\"confidence\":0.96,"
                                + "\"reasoning\":\"登录认证错误\"}",
                        true
                )
        );

        IntentResult result = recognizer.recognize(
                "紧急，订单A12345登录时报错401，支付了99元",
                ""
        );

        assertEquals(IntentCategory.TECHNICAL_LOGIN, result.intent());
        assertEquals(UrgencyLevel.CRITICAL, result.urgency());
        assertEquals("A12345", result.entities().get("order_id").getFirst());
        assertEquals("401", result.entities().get("error_code").getFirst());
        assertEquals("99元", result.entities().get("amount").getFirst());
        assertTrue(result.latencyMs() >= 0.0);
    }

    @Test
    void shouldKeepLegacyConstructorCompatible() {
        IntentRecognizer recognizer =
                new IntentRecognizer(new EntityExtractor());

        IntentResult result = recognizer.recognize(
                "这个问题一直没处理，请帮我转人工客服",
                "旧构造会忽略这段上下文"
        );

        assertEquals(IntentCategory.HUMAN_HANDOFF, result.intent());
        assertEquals(0.95, result.confidence(), 0.000001);
        assertEquals(1, result.sourceScores().size());
        assertEquals(0.95,
                result.sourceScores().get("pattern"), 0.000001);
    }

    private IntentRecognizer fullRecognizer(LlmClient client) {
        return new IntentRecognizer(
                new EntityExtractor(),
                new LlmIntentRecognizer(
                        client,
                        new ObjectMapper(),
                        true
                ),
                TemplateEmbeddingIntentRecognizer.defaults(),
                new PatternIntentRecognizer(),
                IntentFusion.defaults()
        );
    }

    private LlmResponse response(String content, boolean success) {
        return new LlmResponse(
                content,
                "test-model",
                10,
                5,
                1.0,
                success
        );
    }
}
