package com.supportforge.feedback;


public record AnswerRecord(
        String responseId,
        String conversationId,
        String userId,
        String content,
        String createdAt
) {
}
