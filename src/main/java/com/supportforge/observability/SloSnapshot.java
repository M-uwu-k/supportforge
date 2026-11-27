package com.supportforge.observability;

public record SloSnapshot(
        long requests,
        long errors,
        double availability,
        double availabilityTarget,
        double averageLatencyMs,
        long maximumLatencyMs,
        long latencyTargetMs,
        boolean availabilityMet,
        boolean averageLatencyMet
) { }
