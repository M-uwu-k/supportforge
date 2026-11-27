package com.supportforge.tool;

public record ToolContext(
        String userId,
        String conversationId
) {

    public ToolContext {
        userId = userId == null
                ? ""
                : userId.strip();

        conversationId = conversationId == null
                ? ""
                : conversationId.strip();
    }
}