package com.supportforge.llm;

import java.util.Objects;


public record LlmRequest(
        String systemPrompt,
        String userMessage,
        String context,
        double temperature,
        int maxTokens
) {


    public LlmRequest {

        systemPrompt = Objects.requireNonNullElse(
                systemPrompt,
                ""
        );


        userMessage = Objects.requireNonNullElse(
                userMessage,
                ""
        );


        context = Objects.requireNonNullElse(
                context,
                ""
        );


        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2) {

            throw new IllegalArgumentException(
                    "temperature 必须在 0 到 2 之间"
            );
        }


        if (maxTokens <= 0) {
            maxTokens = 2048;
        }
    }
}
