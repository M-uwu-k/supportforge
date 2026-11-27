package com.supportforge.resilience;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyResilienceGuardTest {
    private DependencyResilienceGuard guard;

    @AfterEach
    void close() {
        if (guard != null) {
            guard.close();
        }
    }

    @Test
    void timesOutAndUsesFallback() {
        guard = guard(2);
        String result = guard.execute("slow", () -> {
            Thread.sleep(200);
            return "late";
        }, () -> "fallback");

        assertEquals("fallback", result);
        assertEquals(1, guard.snapshot("slow").degradedCalls());
    }

    @Test
    void opensCircuitAfterFailureThreshold() {
        guard = guard(2);
        AtomicInteger calls = new AtomicInteger();
        for (int index = 0; index < 2; index++) {
            guard.execute("broken", () -> {
                calls.incrementAndGet();
                throw new IllegalStateException("broken");
            }, () -> "fallback");
        }
        guard.execute("broken", () -> {
            calls.incrementAndGet();
            return "unexpected";
        }, () -> "fallback");

        assertTrue(guard.snapshot("broken").open());
        assertEquals(2, calls.get());
    }

    private DependencyResilienceGuard guard(int threshold) {
        return new DependencyResilienceGuard(new ResilienceProperties(
                Duration.ofMillis(30), threshold, Duration.ofSeconds(5),
                2, 10, Duration.ofMinutes(1)
        ));
    }
}
