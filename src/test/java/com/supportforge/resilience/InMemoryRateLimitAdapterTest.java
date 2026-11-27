package com.supportforge.resilience;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryRateLimitAdapterTest {
    @Test
    void rejectsRequestsBeyondWindowLimit() {
        InMemoryRateLimitAdapter limiter = new InMemoryRateLimitAdapter();

        assertTrue(limiter.tryAcquire("tenant:actor", 2, Duration.ofMinutes(1)));
        assertTrue(limiter.tryAcquire("tenant:actor", 2, Duration.ofMinutes(1)));
        assertFalse(limiter.tryAcquire("tenant:actor", 2, Duration.ofMinutes(1)));
        assertTrue(limiter.tryAcquire("tenant:other", 2, Duration.ofMinutes(1)));
    }
}
