package com.supportforge.resilience;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** 统一超时、并发隔离、熔断和fallback，不持有业务数据。 */
@Component
public class DependencyResilienceGuard {
    private final ResilienceProperties properties;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Circuit> circuits = new ConcurrentHashMap<>();

    public DependencyResilienceGuard(ResilienceProperties properties) {
        this.properties = properties;
    }

    public <T> T execute(String dependency, Callable<T> action, Supplier<T> fallback) {
        Circuit circuit = circuits.computeIfAbsent(
                dependency, ignored -> new Circuit(properties.maximumConcurrentCalls())
        );
        if (circuit.openUntilEpochMs.get() > Instant.now().toEpochMilli()) {
            circuit.degraded.incrementAndGet();
            return fallback.get();
        }
        if (!circuit.bulkhead.tryAcquire()) {
            circuit.degraded.incrementAndGet();
            return fallback.get();
        }
        Future<T> future = executor.submit(action);
        try {
            T value = future.get(properties.dependencyTimeout().toMillis(), TimeUnit.MILLISECONDS);
            circuit.failures.set(0);
            return value;
        } catch (Exception exception) {
            future.cancel(true);
            int failures = circuit.failures.incrementAndGet();
            if (failures >= properties.circuitFailureThreshold()) {
                circuit.openUntilEpochMs.set(
                        Instant.now().plus(properties.circuitOpenDuration()).toEpochMilli()
                );
            }
            circuit.degraded.incrementAndGet();
            return fallback.get();
        } finally {
            circuit.bulkhead.release();
        }
    }

    public CircuitSnapshot snapshot(String dependency) {
        Circuit circuit = circuits.get(dependency);
        if (circuit == null) {
            return new CircuitSnapshot(false, 0, 0);
        }
        return new CircuitSnapshot(
                circuit.openUntilEpochMs.get() > Instant.now().toEpochMilli(),
                circuit.failures.get(), circuit.degraded.get()
        );
    }

    @PreDestroy
    public void close() {
        executor.close();
    }

    public record CircuitSnapshot(boolean open, int consecutiveFailures, long degradedCalls) { }

    private static final class Circuit {
        private final Semaphore bulkhead;
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicLong openUntilEpochMs = new AtomicLong();
        private final AtomicLong degraded = new AtomicLong();

        private Circuit(int permits) {
            this.bulkhead = new Semaphore(permits);
        }
    }
}
