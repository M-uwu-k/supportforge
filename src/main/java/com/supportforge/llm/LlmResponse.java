package com.supportforge.llm;


public record LlmResponse(
        String content,
        String model,
        int inputTokens,
        int outputTokens,
        double latencyMs,
        boolean success
) {


}
