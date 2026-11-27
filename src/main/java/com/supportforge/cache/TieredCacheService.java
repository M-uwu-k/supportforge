package com.supportforge.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import com.supportforge.resilience.DependencyResilienceGuard;
import com.supportforge.resilience.ResilienceProperties;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;
import java.time.Duration;
import java.util.concurrent.atomic.LongAdder;

@Service
public class TieredCacheService {

    private final Cache<String, String> l1;

    private final Cache<String, Boolean> bypassL2;
    private final Object[] keyLocks = java.util.stream.Stream.generate(Object::new).limit(64).toArray();
    private final DistributedCachePort l2;
    private final ObjectMapper objectMapper;
    private final TieredCacheProperties properties;
    private final DependencyResilienceGuard resilience;
    private final LongAdder l1Hits = new LongAdder();
    private final LongAdder l2Hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder invalidations = new LongAdder();

    @Autowired
    public TieredCacheService(
            DistributedCachePort l2, ObjectMapper objectMapper,
            TieredCacheProperties properties,
            DependencyResilienceGuard resilience
    ) {
        this(l2, objectMapper, properties, resilience, Ticker.systemTicker());
    }

    TieredCacheService(DistributedCachePort l2, ObjectMapper objectMapper,
                      TieredCacheProperties properties, DependencyResilienceGuard resilience, Ticker ticker) {
        this.l2 = l2;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.resilience = resilience;
        this.l1 = Caffeine.newBuilder()
                .maximumSize(properties.maximumEntries())
                .expireAfterWrite(properties.l1Ttl())
                .ticker(ticker)
                .build();


        Duration bypassTtl = properties.l2Ttl().compareTo(Duration.ofSeconds(1)) < 0
                ? Duration.ofSeconds(1) : properties.l2Ttl();
        this.bypassL2 = Caffeine.newBuilder().expireAfterWrite(bypassTtl).ticker(ticker).build();
    }

    public TieredCacheService(
            DistributedCachePort l2, ObjectMapper objectMapper,
            TieredCacheProperties properties
    ) {
        this(
                l2, objectMapper, properties,
                new DependencyResilienceGuard(new ResilienceProperties(
                        java.time.Duration.ofSeconds(2), 3,
                        java.time.Duration.ofSeconds(10), 50, 100,
                        java.time.Duration.ofMinutes(1)
                ))
        );
    }

    public <T> Optional<T> get(String logicalKey, Class<T> type) {
        String key = namespaced(logicalKey);
        synchronized (keyLock(key)) {
            return getLocked(key, type);
        }
    }

    private <T> Optional<T> getLocked(String key, Class<T> type) {
        String local = l1.getIfPresent(key);
        if (local != null) {
            l1Hits.increment();
            return Optional.of(decode(local, type));
        }

        if (bypassL2.getIfPresent(key) != null) {
            misses.increment();
            return Optional.empty();
        }
        Optional<String> distributed = resilience.execute(
                "valkey", () -> l2.get(key), Optional::empty
        );
        if (distributed.isPresent()) {
            l2Hits.increment();
            l1.put(key, distributed.get());
            return Optional.of(decode(distributed.get(), type));
        }
        misses.increment();
        return Optional.empty();
    }

    public void put(String logicalKey, Object value) {
        String key = namespaced(logicalKey);
        String encoded = objectMapper.writeValueAsString(value);
        synchronized (keyLock(key)) {
            boolean written = resilience.execute("valkey", () -> {
                l2.put(key, encoded, properties.l2Ttl());
                return true;
            }, () -> false);
            if (!written) bypassL2.put(key, true);
            l1.put(key, encoded);
        }
    }

    public void invalidate(String logicalKey) {
        String key = namespaced(logicalKey);

        synchronized (keyLock(key)) {
            l1.invalidate(key);
            boolean deleted = resilience.execute("valkey", () -> {
                l2.delete(key);
                return true;
            }, () -> false);
            if (!deleted) bypassL2.put(key, true);
        }
        invalidations.increment();
    }

    public void clearLocal() {
        l1.invalidateAll();
    }

    public TieredCacheStats stats() {
        return new TieredCacheStats(
                l1Hits.sum(), l2Hits.sum(), misses.sum(), invalidations.sum()
        );
    }

    private String namespaced(String key) {
        return properties.namespace() + ":" + key;
    }

    private Object keyLock(String key) {
        return keyLocks[Math.floorMod(key.hashCode(), keyLocks.length)];
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("缓存JSON损坏", exception);
        }
    }
}
