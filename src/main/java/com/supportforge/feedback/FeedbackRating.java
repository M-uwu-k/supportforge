package com.supportforge.feedback;

import java.util.Locale;


public enum FeedbackRating {

    POSITIVE("positive"),
    NEGATIVE("negative");


    private final String value;


    FeedbackRating(String value) {
        this.value = value;
    }


    public String value() {
        return value;
    }


    public static FeedbackRating fromValue(String value) {

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "评价类型不能为空"
            );
        }


        String normalized =
                value.strip()
                        .toLowerCase(Locale.ROOT);


        for (FeedbackRating rating : values()) {
            if (rating.value.equals(normalized)) {
                return rating;
            }
        }


        throw new IllegalArgumentException(
                "不支持的评价类型："
                        + value
                        + "，只支持 positive 或 negative"
        );
    }
}
