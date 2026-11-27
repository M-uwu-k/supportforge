package com.supportforge.llm;

public interface LlmClient {

    LlmResponse generate(LlmRequest request);
}