package com.supportforge.evaluation;

import java.util.Locale;


public record IntentTestCase(

        String message,

        String expectedIntent
) {

    public IntentTestCase {

        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException(
                    "评测消息不能为空"
            );
        }


        if (expectedIntent == null
                || expectedIntent.isBlank()) {
            throw new IllegalArgumentException(
                    "预期意图不能为空"
            );
        }

        message = message.strip();


        expectedIntent = expectedIntent
                .strip()
                .toLowerCase(Locale.ROOT);

    }
}
