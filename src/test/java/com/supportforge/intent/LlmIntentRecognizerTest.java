package com.supportforge.intent;

import com.supportforge.llm.LlmClient;
import com.supportforge.llm.LlmRequest;
import com.supportforge.llm.LlmResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmIntentRecognizerTest {

    @Test
    void shouldParseSuccessfulJsonAndForwardContext() {
        AtomicReference<LlmRequest> captured = new AtomicReference<>();
        LlmClient client = request -> {
            captured.set(request);
            return response(
                    "{\"intent\":\"refund\","
                            + "\"confidence\":0.91,"
                            + "\"reasoning\":\"用户明确要求退款\"}",
                    true
            );
        };
        LlmIntentRecognizer recognizer = recognizer(client, true);

        IntentCandidate candidate = recognizer.recognize(
                "我要退款",
                "用户之前咨询过订单A100"
        );

        assertEquals(IntentCategory.REFUND, candidate.intent());
        assertEquals(0.91, candidate.confidence(), 0.000001);
        assertEquals("用户明确要求退款", candidate.reasoning());
        assertFalse(candidate.failed());
        assertEquals("我要退款", captured.get().userMessage());
        assertEquals(1024, captured.get().maxTokens());
        assertEquals(
                "用户之前咨询过订单A100",
                captured.get().context()
        );
        assertTrue(captured.get().systemPrompt().contains("payment_issue"));
    }

    @Test
    void shouldExtractJsonFromMarkdownWrappedResponse() {
        LlmClient client = request -> response(
                "分类结果如下：\n```json\n"
                        + "{\"intent\":\"technical_login\","
                        + "\"confidence\":0.88,"
                        + "\"reasoning\":\"登录认证失败\"}"
                        + "\n```",
                true
        );

        IntentCandidate candidate =
                recognizer(client, true)
                        .recognize("登录报401");

        assertEquals(IntentCategory.TECHNICAL_LOGIN, candidate.intent());
        assertEquals(0.88, candidate.confidence(), 0.000001);
        assertFalse(candidate.failed());
    }

    @Test
    void shouldMapUnknownIntentToOtherWithoutMarkingCallFailed() {
        LlmClient client = request -> response(
                "{\"intent\":\"not_a_real_intent\","
                        + "\"confidence\":0.67,"
                        + "\"reasoning\":\"未知标签\"}",
                true
        );

        IntentCandidate candidate =
                recognizer(client, true)
                        .recognize("测试消息");

        assertEquals(IntentCategory.OTHER, candidate.intent());
        assertEquals(0.67, candidate.confidence(), 0.000001);
        assertFalse(candidate.failed());
    }

    @Test
    void shouldReturnFailureForInvalidJsonOrClientFailure() {
        LlmIntentRecognizer invalidJson = recognizer(
                request -> response("这不是JSON", true),
                true
        );
        LlmIntentRecognizer failedClient = recognizer(
                request -> response("", false),
                true
        );

        IntentCandidate invalid = invalidJson.recognize("我要退款");
        IntentCandidate failed = failedClient.recognize("我要退款");

        assertTrue(invalid.failed());
        assertTrue(invalid.reasoning().contains("没有合法JSON对象"));
        assertTrue(failed.failed());
        assertTrue(failed.reasoning().contains("失败状态"));
    }

    @Test
    void shouldNotCallClientWhenLlmRecognitionIsDisabled() {
        AtomicBoolean called = new AtomicBoolean(false);
        LlmClient client = request -> {
            called.set(true);
            return response("{}", true);
        };

        IntentCandidate candidate =
                recognizer(client, false)
                        .recognize("我要退款");

        assertTrue(candidate.failed());
        assertTrue(candidate.reasoning().contains("已关闭"));
        assertFalse(called.get());
    }

    @Test
    void shouldReturnSuccessfulOtherForBlankMessageWithoutCallingClient() {
        AtomicBoolean called = new AtomicBoolean(false);
        LlmClient client = request -> {
            called.set(true);
            return response("{}", true);
        };

        IntentCandidate candidate =
                recognizer(client, true)
                        .recognize("   ");

        assertEquals(IntentCategory.OTHER, candidate.intent());
        assertEquals(0.0, candidate.confidence());
        assertFalse(candidate.failed());
        assertFalse(called.get());
    }

    private LlmIntentRecognizer recognizer(
            LlmClient client,
            boolean enabled
    ) {
        return new LlmIntentRecognizer(
                client,
                new ObjectMapper(),
                enabled
        );
    }

    private LlmResponse response(
            String content,
            boolean success
    ) {
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
