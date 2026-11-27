package com.supportforge.observability;

import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

@Service
public class SloMetricsService {
    private final SloProperties properties;
    private final LongAdder requests = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final LongAdder totalLatencyMs = new LongAdder();
    private final AtomicLong maximumLatencyMs = new AtomicLong();

    public SloMetricsService(SloProperties properties) {
        this.properties = properties;
    }

    public void record(int status, long latencyMs) {
        requests.increment();
        if (status >= 500) {
            errors.increment();
        }
        long safeLatency = Math.max(0, latencyMs);
        totalLatencyMs.add(safeLatency);
        maximumLatencyMs.accumulateAndGet(safeLatency, Math::max);
    }

    public SloSnapshot snapshot() {
        long requestCount = requests.sum();
        long errorCount = errors.sum();
        double availability = requestCount == 0
                ? 1.0 : (requestCount - errorCount) / (double) requestCount;
        double average = requestCount == 0
                ? 0 : totalLatencyMs.sum() / (double) requestCount;
        return new SloSnapshot(
                requestCount, errorCount, availability, properties.availabilityTarget(),
                average, maximumLatencyMs.get(), properties.latencyTargetMs(),
                availability >= properties.availabilityTarget(),
                average <= properties.latencyTargetMs()
        );
    }
}
