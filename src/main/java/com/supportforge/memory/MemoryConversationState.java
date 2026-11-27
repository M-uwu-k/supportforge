package com.supportforge.memory;

import java.time.Instant;
import java.util.List;
import java.util.Map;


public record MemoryConversationState(
        String userId,
        String conversationId,
        List<MemoryMessage> recentMessages,
        String summary,
        Map<String, String> userProfile,
        Instant updatedAt
) {


    public MemoryConversationState {

        ConversationKey key =
                new ConversationKey(
                        userId,
                        conversationId
                );


        userId = key.userId();
        conversationId = key.conversationId();


        recentMessages = recentMessages == null
                ? List.of()
                : List.copyOf(recentMessages);


        summary = summary == null
                ? ""
                : summary.strip();


        userProfile = userProfile == null
                ? Map.of()
                : Map.copyOf(userProfile);


        updatedAt = updatedAt == null
                ? Instant.EPOCH
                : updatedAt;
    }
}
