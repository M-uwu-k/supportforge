package com.supportforge.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(

        @NotBlank(message = "message 不能为空")
        String message,

        String userId,

        String conversationId
) {

    public ChatRequest {
        if (userId == null || userId.isBlank()) {
            userId = "anonymous";
        }
    }
}