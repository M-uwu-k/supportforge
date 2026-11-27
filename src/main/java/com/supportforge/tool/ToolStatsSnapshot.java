package com.supportforge.tool;


public record ToolStatsSnapshot(


        long total,


        long successful,


        long failed,


        long cacheHits,


        long timeouts,


        long circuitRejected,


        double successRate,


        double averageLatencyMs,


        int consecutiveFailures,


        CircuitState circuitState
) {


}
