package com.supportforge.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelevantHistoryRetrieverTest {

    @Test
    void shouldReturnSameUserHistoryAndExcludeCurrentConversation() {
        RelevantHistoryRetriever retriever =
                new RelevantHistoryRetriever(true, 3, 800, 1.0);
        MemorySnapshot snapshot = new MemorySnapshot(
                List.of(
                        state("user-a", "old-login", "登录时报错401", 1),
                        state("user-a", "current", "当前会话也提到了401", 2),
                        state("user-b", "other-user", "另一个用户登录报错401", 3)
                )
        );

        List<String> result = retriever.retrieve(
                snapshot,
                "user-a",
                "current",
                "401怎么处理"
        );

        assertEquals(1, result.size());
        assertTrue(result.getFirst().contains("old-login"));
        assertTrue(result.getFirst().contains("登录时报错401"));
    }

    @Test
    void shouldRankExactMatchFirstAndRespectResultLimit() {
        RelevantHistoryRetriever retriever =
                new RelevantHistoryRetriever(true, 2, 800, 1.0);
        MemorySnapshot snapshot = new MemorySnapshot(
                List.of(
                        state("user", "general-refund", "用户咨询退款流程", 1),
                        state("user", "exact-refund", "退款多久到账", 2),
                        state("user", "another-refund", "退款申请已经提交", 3)
                )
        );

        List<String> result = retriever.retrieve(
                snapshot,
                "user",
                "new-conversation",
                "退款多久到账"
        );

        assertEquals(2, result.size());
        assertTrue(result.getFirst().contains("exact-refund"));
    }

    @Test
    void shouldMatchOrderNumberAndChineseFragments() {
        RelevantHistoryRetriever retriever =
                new RelevantHistoryRetriever(true, 3, 800, 1.0);
        MemorySnapshot snapshot = new MemorySnapshot(
                List.of(
                        state("user", "order-history", "订单A12345正在配送", 1),
                        state("user", "login-history", "账户登录出现异常", 2)
                )
        );

        assertTrue(
                retriever.retrieve(
                                snapshot,
                                "user",
                                "new-1",
                                "A12345到哪里了"
                        )
                        .getFirst()
                        .contains("order-history")
        );

        assertTrue(
                retriever.retrieve(
                                snapshot,
                                "user",
                                "new-2",
                                "登录异常怎么办"
                        )
                        .getFirst()
                        .contains("login-history")
        );
    }

    @Test
    void shouldReturnEmptyWhenDisabledOrQueryBlank() {
        MemorySnapshot snapshot = new MemorySnapshot(
                List.of(state("user", "history", "登录报错401", 1))
        );
        RelevantHistoryRetriever disabled =
                new RelevantHistoryRetriever(false, 3, 800, 1.0);
        RelevantHistoryRetriever enabled =
                new RelevantHistoryRetriever(true, 3, 800, 1.0);

        assertTrue(
                disabled.retrieve(snapshot, "user", "new", "401")
                        .isEmpty()
        );
        assertTrue(
                enabled.retrieve(snapshot, "user", "new", "   ")
                        .isEmpty()
        );
    }

    private MemoryConversationState state(
            String userId,
            String conversationId,
            String content,
            long second
    ) {
        return new MemoryConversationState(
                userId,
                conversationId,
                List.of(
                        new MemoryMessage(
                                MemoryRole.USER,
                                content,
                                Instant.parse("2026-11-05T00:00:00Z")
                                        .plusSeconds(second)
                        )
                ),
                "",
                Map.of(),
                Instant.parse("2026-11-05T00:00:00Z")
                        .plusSeconds(second)
        );
    }
}

