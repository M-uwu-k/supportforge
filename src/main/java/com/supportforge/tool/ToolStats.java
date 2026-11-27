package com.supportforge.tool;

import java.util.concurrent.atomic.LongAdder;


public class ToolStats {


    private final LongAdder total =
            new LongAdder();


    private final LongAdder successful =
            new LongAdder();


    private final LongAdder failed =
            new LongAdder();


    private final LongAdder cacheHits =
            new LongAdder();


    private final LongAdder timeouts =
            new LongAdder();


    private final LongAdder circuitRejected =
            new LongAdder();


    private final LongAdder totalLatencyNanos =
            new LongAdder();


    public void recordCall() {

        total.increment();
    }


    public void recordSuccess(
            double latencyMs,
            boolean cached
    ) {
        successful.increment();


        if (cached) {
            cacheHits.increment();
        }


        recordLatency(latencyMs);
    }


    public void recordFailure(double latencyMs) {
        failed.increment();
        recordLatency(latencyMs);
    }


    public void recordTimeout(double latencyMs) {
        timeouts.increment();

        recordFailure(latencyMs);
    }


    public void recordCircuitRejected(
            double latencyMs
    ) {
        circuitRejected.increment();

        recordFailure(latencyMs);
    }


    public ToolStatsSnapshot snapshot(
            CircuitBreaker circuitBreaker
    ) {
        long totalValue = total.sum();
        long successValue = successful.sum();


        double successRate =
                totalValue == 0
                        ? 1.0
                        : (double) successValue
                        / totalValue;


        double averageLatencyMs =
                totalValue == 0
                        ? 0.0
                        : totalLatencyNanos.sum()
                        / 1_000_000.0
                        / totalValue;


        return new ToolStatsSnapshot(
                totalValue,
                successValue,
                failed.sum(),
                cacheHits.sum(),
                timeouts.sum(),
                circuitRejected.sum(),
                successRate,
                averageLatencyMs,
                circuitBreaker
                        .consecutiveFailures(),
                circuitBreaker.state()
        );
    }


    private void recordLatency(double latencyMs) {


        if (!Double.isFinite(latencyMs)
                || latencyMs < 0) {

            return;
        }


        totalLatencyNanos.add(
                (long) (latencyMs * 1_000_000.0)
        );
    }
}
