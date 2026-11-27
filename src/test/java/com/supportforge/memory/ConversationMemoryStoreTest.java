package com.supportforge.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationMemoryStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldSaveAndLoadSnapshot() {
        Path path = tempDir.resolve("nested/conversation-memory.json");
        ConversationMemoryStore store = store(path, 10, 12);
        MemorySnapshot snapshot = new MemorySnapshot(
                List.of(state("user-1", "conversation-1", "你好")),
                Map.of(
                        "user-1",
                        Map.of("language", "zh-CN")
                )
        );

        store.save(snapshot);

        assertTrue(Files.exists(path));
        assertEquals(snapshot, store.load());
    }

    @Test
    void shouldRestoreMessagesAfterMemoryInstanceRestart() {
        ConversationMemoryStore store = storeAt("restart/memory.json", 10, 12);
        InMemoryConversationMemory firstInstance =
                new InMemoryConversationMemory(store);

        firstInstance.append(
                "restart-user",
                "restart-conversation",
                message(MemoryRole.USER, "重启前的问题", 1)
        );
        firstInstance.append(
                "restart-user",
                "restart-conversation",
                message(MemoryRole.ASSISTANT, "重启前的回答", 2)
        );

        InMemoryConversationMemory restartedInstance =
                new InMemoryConversationMemory(store);

        assertEquals(
                List.of("重启前的问题", "重启前的回答"),
                restartedInstance.getRecent(
                                "restart-user",
                                "restart-conversation"
                        )
                        .stream()
                        .map(MemoryMessage::content)
                        .toList()
        );
    }

    @Test
    void shouldPersistConversationDeletion() {
        ConversationMemoryStore store = storeAt("clear/memory.json", 10, 12);
        InMemoryConversationMemory firstInstance =
                new InMemoryConversationMemory(store);

        firstInstance.append(
                "clear-user",
                "clear-conversation",
                message(MemoryRole.USER, "即将清除", 1)
        );
        firstInstance.clear("clear-user", "clear-conversation");

        InMemoryConversationMemory restartedInstance =
                new InMemoryConversationMemory(store);

        assertTrue(
                restartedInstance.getRecent(
                        "clear-user",
                        "clear-conversation"
                ).isEmpty()
        );
        assertTrue(store.load().conversations().isEmpty());
    }

    @Test
    void shouldKeepOnlyNewestMessages() {
        ConversationMemoryStore store = storeAt("message-limit/memory.json", 10, 3);
        InMemoryConversationMemory memory =
                new InMemoryConversationMemory(store);

        memory.append("user", "conversation", message(MemoryRole.USER, "m1", 1));
        memory.append("user", "conversation", message(MemoryRole.ASSISTANT, "m2", 2));
        memory.append("user", "conversation", message(MemoryRole.USER, "m3", 3));
        memory.append("user", "conversation", message(MemoryRole.ASSISTANT, "m4", 4));

        assertEquals(
                List.of("m2", "m3", "m4"),
                memory.getRecent("user", "conversation")
                        .stream()
                        .map(MemoryMessage::content)
                        .toList()
        );
    }

    @Test
    void shouldCompressOldMessagesIntoSummaryAndRestoreAfterRestart() {
        ConversationMemoryStore store = storeAt("summary-restart/memory.json", 10, 3);
        ConversationSummarizer summarizer = ConversationSummarizer.local();
        InMemoryConversationMemory memory =
                new InMemoryConversationMemory(store, summarizer, 2);

        memory.append("user", "summary-conversation", message(MemoryRole.USER, "m1旧问题", 1));
        memory.append("user", "summary-conversation", message(MemoryRole.ASSISTANT, "m2旧回答", 2));
        memory.append("user", "summary-conversation", message(MemoryRole.USER, "m3新问题", 3));
        memory.append("user", "summary-conversation", message(MemoryRole.ASSISTANT, "m4新回答", 4));

        MemoryContext current = memory.getContext(
                "user",
                "summary-conversation",
                ""
        );

        assertTrue(current.summary().contains("m1旧问题"));
        assertTrue(current.summary().contains("m2旧回答"));
        assertEquals(
                List.of("m3新问题", "m4新回答"),
                current.recentMessages().stream()
                        .map(MemoryMessage::content)
                        .toList()
        );

        InMemoryConversationMemory restarted =
                new InMemoryConversationMemory(store, summarizer, 2);
        MemoryContext restored = restarted.getContext(
                "user",
                "summary-conversation",
                ""
        );

        assertEquals(current.summary(), restored.summary());
        assertEquals(current.recentMessages(), restored.recentMessages());
        assertTrue(restored.toPrompt().contains("[对话摘要]"));
    }

    @Test
    void shouldAccumulateSummaryAcrossMultipleCompressionBatches() {
        ConversationMemoryStore store = storeAt("summary-accumulate/memory.json", 10, 3);
        InMemoryConversationMemory memory =
                new InMemoryConversationMemory(
                        store,
                        ConversationSummarizer.local(),
                        2
                );

        for (int index = 1; index <= 6; index++) {
            memory.append(
                    "user",
                    "accumulate-conversation",
                    message(
                            index % 2 == 0
                                    ? MemoryRole.ASSISTANT
                                    : MemoryRole.USER,
                            "m" + index,
                            index
                    )
            );
        }

        MemoryContext context = memory.getContext(
                "user",
                "accumulate-conversation",
                ""
        );

        assertTrue(context.summary().contains("m1"));
        assertTrue(context.summary().contains("m2"));
        assertTrue(context.summary().contains("m3"));
        assertTrue(context.summary().contains("m4"));
        assertEquals(
                List.of("m5", "m6"),
                context.recentMessages().stream()
                        .map(MemoryMessage::content)
                        .toList()
        );
    }

    @Test
    void shouldRetrievePersistedHistoryForNewConversationAfterRestart() {
        ConversationMemoryStore store = storeAt("history-restart/memory.json", 10, 12);
        InMemoryConversationMemory firstInstance =
                new InMemoryConversationMemory(store);

        firstInstance.append(
                "history-user",
                "old-login-conversation",
                message(MemoryRole.USER, "上次登录时报错401", 1)
        );
        firstInstance.append(
                "other-user",
                "private-conversation",
                message(MemoryRole.USER, "另一个用户也遇到401", 2)
        );

        InMemoryConversationMemory restarted =
                new InMemoryConversationMemory(store);
        MemoryContext context = restarted.getContext(
                "history-user",
                "brand-new-conversation",
                "之前的401怎么处理"
        );

        assertTrue(context.recentMessages().isEmpty());
        assertEquals(1, context.relevantHistory().size());
        assertTrue(context.relevantHistory().getFirst()
                .contains("old-login-conversation"));
        assertTrue(context.toPrompt().contains("[相关历史]"));
        assertTrue(context.toPrompt().contains("上次登录时报错401"));
    }

    @Test
    void shouldKeepOnlyNewestConversations() {
        ConversationMemoryStore store = storeAt("conversation-limit/memory.json", 2, 12);
        InMemoryConversationMemory memory =
                new InMemoryConversationMemory(store);

        memory.append("user", "c1", message(MemoryRole.USER, "first", 1));
        memory.append("user", "c2", message(MemoryRole.USER, "second", 2));
        memory.append("user", "c3", message(MemoryRole.USER, "third", 3));

        assertTrue(memory.getRecent("user", "c1").isEmpty());
        assertEquals("second", memory.getRecent("user", "c2").getFirst().content());
        assertEquals("third", memory.getRecent("user", "c3").getFirst().content());
        assertEquals(2, store.load().conversations().size());
    }

    @Test
    void shouldKeepUsersIsolatedAfterRestart() {
        ConversationMemoryStore store = storeAt("isolation/memory.json", 10, 12);
        InMemoryConversationMemory memory =
                new InMemoryConversationMemory(store);

        memory.append("user-a", "same-conversation", message(MemoryRole.USER, "A的问题", 1));
        memory.append("user-b", "same-conversation", message(MemoryRole.USER, "B的问题", 2));

        InMemoryConversationMemory restarted =
                new InMemoryConversationMemory(store);

        assertEquals(
                "A的问题",
                restarted.getRecent("user-a", "same-conversation")
                        .getFirst()
                        .content()
        );
        assertEquals(
                "B的问题",
                restarted.getRecent("user-b", "same-conversation")
                        .getFirst()
                        .content()
        );
    }

    private ConversationMemoryStore storeAt(
            String relativePath,
            int maxConversations,
            int maxMessages
    ) {
        return store(
                tempDir.resolve(relativePath),
                maxConversations,
                maxMessages
        );
    }

    private ConversationMemoryStore store(
            Path path,
            int maxConversations,
            int maxMessages
    ) {
        return new ConversationMemoryStore(
                new ObjectMapper(),
                path.toString(),
                maxConversations,
                maxMessages
        );
    }

    private MemoryConversationState state(
            String userId,
            String conversationId,
            String content
    ) {
        return new MemoryConversationState(
                userId,
                conversationId,
                List.of(message(MemoryRole.USER, content, 1)),
                "摘要",
                Map.of("language", "zh-CN"),
                Instant.parse("2026-11-05T00:00:10Z")
        );
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
