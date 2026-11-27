package com.supportforge.api.dto;


public record FeedbackRequest(
        String responseId,
        String conversationId,
        String rating,
        String comment
) {

    public FeedbackRequest {

        if (responseId == null
                || responseId.isBlank()) {
            throw new IllegalArgumentException(
                    "回答编号不能为空"
            );
        }

        if (conversationId == null
                || conversationId.isBlank()) {
            throw new IllegalArgumentException(
                    "会话编号不能为空"
            );
        }

        if (rating == null
                || rating.isBlank()) {
            throw new IllegalArgumentException(
                    "评价类型不能为空"
            );
        }


        responseId = responseId.strip();
        conversationId = conversationId.strip();
        rating = rating.strip();


        comment = comment == null
                ? ""
                : comment.strip();


        if (comment.length() > 500) {
            throw new IllegalArgumentException(
                    "评价说明不能超过500个字符"
            );
        }
    }
}
