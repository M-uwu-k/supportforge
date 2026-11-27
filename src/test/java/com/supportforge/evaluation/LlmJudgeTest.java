package com.supportforge.evaluation;

import com.supportforge.llm.LlmClient;
import com.supportforge.llm.LlmRequest;
import com.supportforge.llm.LlmResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmJudgeTest {

    @Test
    void shouldParseCompleteEmbeddedJsonAndClampScores() {
        AtomicReference<LlmRequest> captured =
                new AtomicReference<>();
        LlmClient client = request -> {
            captured.set(request);
            return response(
                    """
                    评分如下：
                    ```json
                    {
                      "relevance": 1.2,
                      "accuracy": 0.8,
                      "completeness": 0.7,
                      "helpfulness": 0.6
                    }
                    ```
                    """,
                    true
            );
        };
        LlmJudge judge = judge(client);

        QualityScores scores = judge.judge(
                "登录报401怎么办？",
                "请重新登录并检查凭证是否过期。",
                "用户此前已尝试重启应用。"
        );

        assertFalse(scores.judgeFailed());
        assertEquals(1.0, scores.relevance());
        assertEquals(0.8, scores.accuracy());
        assertEquals(0.7, scores.completeness());
        assertEquals(0.6, scores.helpfulness());
        assertEquals(0.775, scores.overall());
        assertTrue(captured.get().systemPrompt()
                .contains("客服回答质量评估员"));
        assertTrue(captured.get().userMessage()
                .contains("登录报401怎么办？"));
        assertTrue(captured.get().userMessage()
                .contains("用户此前已尝试重启应用。"));
        assertEquals(0.0, captured.get().temperature());
        assertEquals(2048, captured.get().maxTokens());
    }

    @Test
    void incompleteScoreObjectsAreReportedAsJudgeFailure() {
        for (String json : new String[]{"{}", "{\"relevance\":0.9,\"accuracy\":0.8,\"completeness\":0.7}",
                "{\"relevance\":null,\"accuracy\":0.8,\"completeness\":0.7,\"helpfulness\":0.9}"}) {
            QualityScores scores = judge(request -> response(json, true)).judge("问题", "回答", "");
            assertTrue(scores.judgeFailed());
            assertEquals("评判模型评分字段缺失或不是有效数字", scores.error());
        }
    }

    @Test
    void shouldReturnFailureScoresForClientAndJsonFailures() {
        QualityScores clientFailure = judge(request ->
                response("", false)
        ).judge("问题", "回答", null);
        assertTrue(clientFailure.judgeFailed());
        assertEquals("评判模型调用失败", clientFailure.error());
        assertEquals(0.5, clientFailure.overall());

        QualityScores invalidJson = judge(request ->
                response("这不是JSON", true)
        ).judge("问题", "回答", null);
        assertTrue(invalidJson.judgeFailed());
        assertEquals(
                "评判模型没有返回有效JSON",
                invalidJson.error()
        );

        QualityScores exceptionFailure = judge(request -> {
            throw new IllegalStateException("judge backend unavailable");
        }).judge("问题", "回答", null);
        assertTrue(exceptionFailure.judgeFailed());
        assertEquals(
                "judge backend unavailable",
                exceptionFailure.error()
        );
    }

    @Test
    void shouldNormalizeQualityScores() {
        QualityScores scores = new QualityScores(
                -0.2,
                1.4,
                Double.NaN,
                0.33333,
                false,
                "   "
        );

        assertEquals(0.0, scores.relevance());
        assertEquals(1.0, scores.accuracy());
        assertEquals(0.5, scores.completeness());
        assertEquals(0.33333, scores.helpfulness());
        assertEquals(0.4583, scores.overall());
        assertEquals(null, scores.error());
    }

    private LlmJudge judge(LlmClient client) {
        return new LlmJudge(
                client,
                new ObjectMapper()
        );
    }

    private LlmResponse response(
            String content,
            boolean success
    ) {
        return new LlmResponse(
                content,
                "judge-test",
                10,
                5,
                1.0,
                success
        );
    }
}
