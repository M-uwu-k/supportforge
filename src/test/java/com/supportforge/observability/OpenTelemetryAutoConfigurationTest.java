package com.supportforge.observability;

import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 防回归测试：验证第22步不只是引入API，而是真正装配了Micrometer Tracer和OpenTelemetry SDK。
 */
@SpringBootTest(properties = {
        "management.tracing.sampling.probability=1.0",
        "management.tracing.export.otlp.enabled=false"
})
class OpenTelemetryAutoConfigurationTest {

    @Autowired
    private Tracer tracer;

    @Autowired
    private OpenTelemetry openTelemetry;

    @Test
    void shouldAutoConfigureMicrometerAndOpenTelemetryTracing() {
        assertThat(tracer).isNotNull();
        assertThat(openTelemetry).isNotNull();
    }
}
