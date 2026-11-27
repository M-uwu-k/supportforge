package com.supportforge.monitor;

import com.supportforge.agent.AgentResponse;
import com.supportforge.agent.AgentType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;


class PerformanceMonitorRoutingPenaltyTest {


    @Test
    void shouldReturnZeroPenaltyBeforeAgentHasSamples() {
        SimpleMeterRegistry registry =
                new SimpleMeterRegistry();

        try {

            PerformanceMonitor monitor =
                    new PerformanceMonitor(registry);

            assertEquals(
                    0.0,
                    monitor.routingPenalty(
                            AgentType.TECHNICAL
                    )
            );
        } finally {
            registry.close();
        }
    }


    @Test
    void shouldCombineSuccessRateAndLatencyPenalty() {
        SimpleMeterRegistry registry =
                new SimpleMeterRegistry();

        try {

            PerformanceMonitor monitor =
                    new PerformanceMonitor(registry);

            monitor.recordAgent(
                    new AgentResponse(
                            AgentType.TECHNICAL,
                            "failed",
                            false,
                            0.0,
                            7_000.0,
                            false,
                            List.of()
                    )
            );


            assertEquals(
                    0.9,
                    monitor.routingPenalty(
                            AgentType.TECHNICAL
                    ),
                    0.000_001
            );
        } finally {
            registry.close();
        }
    }
}
