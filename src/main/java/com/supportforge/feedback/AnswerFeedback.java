package com.supportforge.feedback;


public record AnswerFeedback(


        String id,


        String responseId,


        String conversationId,


        String rating,


        String comment,


        String submittedAt
) {
}
