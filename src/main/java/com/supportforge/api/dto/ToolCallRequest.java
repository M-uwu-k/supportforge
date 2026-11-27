package com.supportforge.api.dto;

import java.util.Map;

public record ToolCallRequest(
        Map<String, Object> parameters,
        String userId,
        String conversationId
) {

    public ToolCallRequest {
        parameters = parameters == null
                ? Map.of()
                : Map.copyOf(parameters);

        userId = userId == null
                ? ""
                : userId;

        conversationId =
                conversationId == null
                        ? ""
                        : conversationId;
    }
}