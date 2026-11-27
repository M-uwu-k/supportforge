package com.supportforge.memory;

import com.supportforge.llm.LlmClient;
import com.supportforge.llm.LlmRequest;
import com.supportforge.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationSummarizerTest {

    @Test
    void shouldBuildLocalSummaryWithoutCallingLlm() {
        AtomicBoolean called = new AtomicBoolean(false);
        LlmClient client = request -> {
            called.set(true);
            throw new AssertionError("本地摘要不应该调用LLM");
        };
        ConversationSummarizer summarizer =
                new ConversationSummarizer(client, false, 2000, 100);

        String summary = summarizer.summarize(
                "用户此前咨询登录问题",
                List.of(
                        message(MemoryRole.USER, "错误码401", 1),
                        message(MemoryRole.ASSISTANT, "请检查登录状态", 2)
                )
        );

        assertFalse(called.get());
        assertTrue(summary.contains("用户此前咨询登录问题"));
        assertTrue(summary.contains("用户：错误码401"));
        assertTrue(summary.contains("助手：请检查登录状态"));
    }

    @Test
    void shouldUseSuccessfulRealModelSummary() {
        AtomicReference<LlmRequest> captured = new AtomicReference<>();
        LlmClient client = request -> {
            captured.set(request);
            return new LlmResponse(
                    "用户遇到401登录错误，正在检查登录状态。",
                    "real-summary-model",
                    30,
                    12,
                    5.0,
                    true
            );
        };
        ConversationSummarizer summarizer =
                new ConversationSummarizer(client, true, 2000, 120);

        String summary = summarizer.summarize(
                "",
                List.of(message(MemoryRole.USER, "登录时报错401", 1))
        );

        assertEquals("用户遇到401登录错误，正在检查登录状态。", summary);
        assertTrue(captured.get().systemPrompt()
                .contains("TASK:CONVERSATION_SUMMARY"));
        assertTrue(captured.get().context().contains("登录时报错401"));
        assertEquals(120, captured.get().maxTokens());
    }

    @Test
    void shouldFallbackWhenLlmThrowsException() {
        LlmClient client = request -> {
            throw new IllegalStateException("模型暂时不可用");
        };
        ConversationSummarizer summarizer =
                new ConversationSummarizer(client, true, 2000, 100);

        String summary = summarizer.summarize(
                "已有摘要",
                List.of(message(MemoryRole.USER, "需要退款", 1))
        );

        assertTrue(summary.contains("已有摘要"));
        assertTrue(summary.contains("用户：需要退款"));
    }

    @Test
    void shouldFallbackForMockModelAndRespectCharacterLimit() {
        LlmClient client = request -> new LlmResponse(
                "这不是有效摘要",
                "mock-v1",
                10,
                5,
                1.0,
                true
        );
        ConversationSummarizer summarizer =
                new ConversationSummarizer(client, true, 12, 100);

        String summary = summarizer.summarize(
                "很早以前的信息",
                List.of(message(MemoryRole.USER, "最后的新信息", 1))
        );

        assertTrue(summary.length() <= 12);
        assertTrue(summary.endsWith("最后的新信息"));
    }

    private MemoryMessage message(
            MemoryRole role,
            String content,
            long second
    ) {
        return new MemoryMessage(
                role,
                content,
                Instant.parse("2026-11-05T00:00:00Z")
                        .plusSeconds(second)
        );
    }
}

