package com.supportforge.resilience;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(
        prefix = "supportforge.features", name = "distributed-cache",
        havingValue = "false", matchIfMissing = true
)
public class InMemoryRateLimitAdapter implements RateLimitPort {
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String key, int maximumRequests, Duration window) {
        Instant now = Instant.now();
        Window updated = windows.compute(key, (ignored, current) -> {
            if (current == null || !current.expiresAt().isAfter(now)) {
                return new Window(1, now.plus(window));
            }
            return new Window(current.count() + 1, current.expiresAt());
        });
        return updated.count() <= maximumRequests;
    }

    private record Window(int count, Instant expiresAt) { }
}
