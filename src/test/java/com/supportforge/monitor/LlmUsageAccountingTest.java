package com.supportforge.monitor;

import com.supportforge.llm.LlmResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmUsageAccountingTest {
    @Test
    void reportedTokensAreCountedEvenWhenTheAnswerWasTruncated() {
        var registry = new SimpleMeterRegistry();
        try {
            new PerformanceMonitor(registry).recordLlm(new LlmResponse("truncated", "test-model", 23, 4096, 10, false));
            assertThat(registry.get("supportforge.llm.tokens").tag("type", "input").counter().count()).isEqualTo(23);
            assertThat(registry.get("supportforge.llm.tokens").tag("type", "output").counter().count()).isEqualTo(4096);
            assertThat(registry.get("supportforge.llm.calls").tag("success", "false").counter().count()).isEqualTo(1);
        } finally { registry.close(); }
    }
}
