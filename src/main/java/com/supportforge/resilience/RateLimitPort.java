package com.supportforge.resilience;

import java.time.Duration;

public interface RateLimitPort {
    boolean tryAcquire(String key, int maximumRequests, Duration window);
}
