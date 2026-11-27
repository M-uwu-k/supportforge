package com.supportforge.api.dto;

import com.supportforge.intent.IntentCategory;


public record IntentLearningRequest(
        String message,
        String correctIntent
) {


    public IntentLearningRequest {

        if (message == null
                || message.isBlank()) {


            throw new IllegalArgumentException(
                    "意图学习消息不能为空"
            );
        }


        if (correctIntent == null
                || correctIntent.isBlank()) {

            throw new IllegalArgumentException(
                    "正确意图不能为空"
            );
        }

        message = message.strip();
        correctIntent =
                correctIntent.strip()
                        .toLowerCase();
    }


    public IntentCategory category() {
        IntentCategory category =
                IntentCategory.fromValue(
                        correctIntent
                );

        if (category == IntentCategory.OTHER) {

            throw new IllegalArgumentException(
                    "正确意图不存在或不能为other："
                            + correctIntent
            );
        }

        return category;
    }
}
