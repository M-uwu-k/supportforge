package com.supportforge.cache;

import java.time.Duration;
import java.util.Optional;

public interface DistributedCachePort {
    Optional<String> get(String key);
    void put(String key, String value, Duration ttl);
    void delete(String key);
}
