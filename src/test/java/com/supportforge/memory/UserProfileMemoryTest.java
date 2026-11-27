package com.supportforge.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserProfileMemoryTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldApplyPreferenceImmediatelyBeforeItIsPersisted() {
        InMemoryConversationMemory memory = memory("immediate.json");

        MemoryContext context = memory.getContext(
                "user-1",
                "conversation-1",
                "以后请用英文回答，简洁一点"
        );

        assertEquals("英文", context.userProfile().get("preferred_language"));
        assertEquals("简洁", context.userProfile().get("response_style"));
        assertTrue(context.toPrompt().contains("[用户画像]"));
    }

    @Test
    void shouldReuseAndOverrideProfileAcrossConversations() {
        InMemoryConversationMemory memory = memory("cross-conversation.json");

        memory.append(
                "user-1",
                "conversation-1",
                message("以后请用英文回答，简洁一点")
        );

        MemoryContext reused = memory.getContext(
                "user-1",
                "conversation-2",
                "订单到哪里了"
        );

        assertEquals("英文", reused.userProfile().get("preferred_language"));
        assertEquals("简洁", reused.userProfile().get("response_style"));

        memory.append(
                "user-1",
                "conversation-2",
                message("以后请用中文详细一点回答")
        );

        MemoryContext overridden = memory.getContext(
                "user-1",
                "conversation-3",
                "继续"
        );

        assertEquals(
                Map.of(
                        "preferred_language", "中文",
                        "response_style", "详细"
                ),
                overridden.userProfile()
        );
    }

    @Test
    void shouldKeepProfilesIsolatedBetweenUsers() {
        InMemoryConversationMemory memory = memory("user-isolation.json");

        memory.append(
                "user-a",
                "conversation",
                message("以后请用英文回答")
        );
        memory.append(
                "user-b",
                "conversation",
                message("以后请用中文回答")
        );

        assertEquals(
                "英文",
                memory.getContext("user-a", "new", "继续")
                        .userProfile()
                        .get("preferred_language")
        );
        assertEquals(
                "中文",
                memory.getContext("user-b", "new", "继续")
                        .userProfile()
                        .get("preferred_language")
        );
    }

    @Test
    void shouldRestoreProfileAfterRestart() {
        ConversationMemoryStore store = store("restart.json");
        InMemoryConversationMemory first = new InMemoryConversationMemory(store);

        first.append(
                "restart-user",
                "old-conversation",
                message("以后请用英文回答，简洁一点")
        );

        InMemoryConversationMemory restarted =
                new InMemoryConversationMemory(store);
        MemoryContext restored = restarted.getContext(
                "restart-user",
                "new-conversation",
                "查看订单"
        );

        assertEquals("英文", restored.userProfile().get("preferred_language"));
        assertEquals("简洁", restored.userProfile().get("response_style"));
    }

    @Test
    void shouldKeepGlobalProfileAfterClearingLastConversationAndRestarting() {
        ConversationMemoryStore store = store("clear.json");
        InMemoryConversationMemory memory = new InMemoryConversationMemory(store);

        memory.append(
                "clear-user",
                "old-conversation",
                message("以后请用英文回答，简洁一点")
        );
        memory.clear("clear-user", "old-conversation");

        InMemoryConversationMemory restarted =
                new InMemoryConversationMemory(store);
        MemoryContext restored = restarted.getContext(
                "clear-user",
                "new-conversation",
                "继续"
        );

        assertTrue(restored.recentMessages().isEmpty());
        assertEquals("英文", restored.userProfile().get("preferred_language"));
        assertEquals("简洁", restored.userProfile().get("response_style"));
    }

    private InMemoryConversationMemory memory(String fileName) {
        return new InMemoryConversationMemory(store(fileName));
    }

    private ConversationMemoryStore store(String fileName) {
        return new ConversationMemoryStore(
                new ObjectMapper(),
                tempDir.resolve(fileName).toString(),
                20,
                12
        );
    }

    private MemoryMessage message(String content) {
        return new MemoryMessage(
                MemoryRole.USER,
                content,
                Instant.parse("2026-11-06T00:00:00Z")
        );
    }
}
