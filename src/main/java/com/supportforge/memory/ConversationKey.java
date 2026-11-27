package com.supportforge.memory;


public record ConversationKey(
        String userId,
        String conversationId
) {

    public ConversationKey {

        userId = userId == null || userId.isBlank()
                ? "anonymous"
                : userId.strip();


        if (conversationId == null
                || conversationId.isBlank()) {
            throw new IllegalArgumentException(
                    "会话编号不能为空"
            );
        }

        conversationId = conversationId.strip();
    }
}
