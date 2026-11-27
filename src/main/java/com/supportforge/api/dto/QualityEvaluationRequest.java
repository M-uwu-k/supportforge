package com.supportforge.api.dto;


public record QualityEvaluationRequest(
        String question,
        String response,
        String context
) {
    public QualityEvaluationRequest {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException(
                    "评测问题不能为空"
            );
        }

        if (response == null || response.isBlank()) {
            throw new IllegalArgumentException(
                    "待评测回答不能为空"
            );
        }

        question = question.strip();
        response = response.strip();

        context = context == null
                ? ""
                : context.strip();
    }
}