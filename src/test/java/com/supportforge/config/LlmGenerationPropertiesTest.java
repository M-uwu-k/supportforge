package com.supportforge.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LlmGenerationPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void bindsSeparateBudgetsAndTimeoutsWithoutLosingDefaults() {
        runner.withPropertyValues("supportforge.llm.chat-max-tokens=8192", "supportforge.llm.read-timeout=150s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(LlmGenerationProperties.class);
                    assertThat(properties.chatMaxTokens()).isEqualTo(8192);
                    assertThat(properties.intentMaxTokens()).isEqualTo(1024);
                    assertThat(properties.judgeMaxTokens()).isEqualTo(2048);
                    assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(150));
                });
    }

    @Test
    void refusesNonpositiveBudgetsAndInfiniteTimeoutsAtStartup() {
        for (String invalid : new String[]{"supportforge.llm.judge-max-tokens=0", "supportforge.llm.read-timeout=0s"}) {
            runner.withPropertyValues(invalid).run(context -> assertThat(context).hasFailed());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LlmGenerationProperties.class)
    static class Config {}

    @Test
    void nonfiniteTemperatureCannotReachTheModelClient() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                new com.supportforge.llm.LlmRequest("", "question", "", Double.NaN, 2048));
    }
}
