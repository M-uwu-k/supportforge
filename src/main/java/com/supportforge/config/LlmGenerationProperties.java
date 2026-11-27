package com.supportforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** 各任务的输出预算与HTTP超时。上限不是预付用量，实际费用仍取决于真实生成的token。 */
@ConfigurationProperties("supportforge.llm")
public record LlmGenerationProperties(
        @DefaultValue("4096") int chatMaxTokens,
        @DefaultValue("2048") int specialistMaxTokens,
        @DefaultValue("1024") int intentMaxTokens,
        @DefaultValue("2048") int judgeMaxTokens,
        @DefaultValue("10s") Duration connectTimeout,
        @DefaultValue("120s") Duration readTimeout
) {
    public LlmGenerationProperties {
        if (chatMaxTokens < 1 || specialistMaxTokens < 1 || intentMaxTokens < 1 || judgeMaxTokens < 1) {
            throw new IllegalArgumentException("LLM输出token上限必须大于0");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                || readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("LLM连接和响应超时必须大于0");
        }
    }

    public static LlmGenerationProperties defaults() {
        return new LlmGenerationProperties(4096, 2048, 1024, 2048,
                Duration.ofSeconds(10), Duration.ofSeconds(120));
    }
}
