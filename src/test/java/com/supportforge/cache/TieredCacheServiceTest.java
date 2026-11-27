package com.supportforge.cache;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import com.supportforge.resilience.DependencyResilienceGuard;
import com.supportforge.resilience.ResilienceProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TieredCacheServiceTest {

    @Test
    void failedDeleteBypassesOldL2WithoutDisablingOtherKeys() {
        AtomicLong nanos = new AtomicLong();
        FaultCache l2 = new FaultCache(nanos);
        DependencyResilienceGuard guard = guard();
        try {
            TieredCacheService cache = cache(l2, guard, nanos);
            cache.put("changed", new Value("old"));
            cache.put("unrelated", new Value("valid"));
            l2.failDelete = true;
            cache.invalidate("changed");
            cache.clearLocal();

            assertTrue(cache.get("changed", Value.class).isEmpty());
            assertEquals("valid", cache.get("unrelated", Value.class).orElseThrow().text());
            assertEquals(1, cache.stats().l2Hits());
        } finally { guard.close(); }
    }

    @Test
    void failedWriteCannotResurrectPreviousL2ValueAfterL1Expires() {
        AtomicLong nanos = new AtomicLong();
        FaultCache l2 = new FaultCache(nanos);
        DependencyResilienceGuard guard = guard();
        try {
            TieredCacheService cache = cache(l2, guard, nanos);
            cache.put("answer", new Value("old"));
            nanos.set(Duration.ofSeconds(2).toNanos());
            l2.failPut = true;
            cache.put("answer", new Value("new"));
            assertEquals("new", cache.get("answer", Value.class).orElseThrow().text());

            nanos.set(Duration.ofSeconds(4).toNanos());
            assertTrue(cache.get("answer", Value.class).isEmpty());

            cache.put("answer", new Value("new"));
            nanos.set(Duration.ofSeconds(13).toNanos());
            assertEquals("new", cache.get("answer", Value.class).orElseThrow().text());
            assertEquals(1, cache.stats().l2Hits());
        } finally { guard.close(); }
    }

    private DependencyResilienceGuard guard() {
        return new DependencyResilienceGuard(new ResilienceProperties(Duration.ofSeconds(2), 3,
                Duration.ofSeconds(10), 50, 100, Duration.ofMinutes(1)));
    }

    @Test
    void quarantineCoversValkeyMinimumTtlForSubsecondConfiguration() {
        AtomicLong nanos = new AtomicLong();
        FaultCache l2 = new FaultCache(nanos);
        DependencyResilienceGuard guard = guard();
        try {
            TieredCacheService cache = new TieredCacheService(l2, new ObjectMapper(), new TieredCacheProperties(100,
                    Duration.ofMillis(100), Duration.ofMillis(500), "redis://unused:6379", "test"), guard, nanos::get);
            cache.put("answer", new Value("old"));
            l2.failDelete = true;
            cache.invalidate("answer");
            nanos.set(Duration.ofMillis(750).toNanos());
            assertTrue(cache.get("answer", Value.class).isEmpty());
        } finally { guard.close(); }
    }

    private TieredCacheService cache(FaultCache l2, DependencyResilienceGuard guard, AtomicLong nanos) {
        return new TieredCacheService(l2, new ObjectMapper(), new TieredCacheProperties(100,
                Duration.ofSeconds(1), Duration.ofSeconds(10), "redis://unused:6379", "test"), guard, nanos::get);
    }

    private static final class FaultCache implements DistributedCachePort {
        private record Entry(String value, long expiresAt) { }
        private final Map<String, Entry> entries = new ConcurrentHashMap<>();
        private final AtomicLong nanos;
        volatile boolean failDelete;
        volatile boolean failPut;
        FaultCache(AtomicLong nanos) { this.nanos = nanos; }
        public Optional<String> get(String key) {
            Entry value = entries.get(key);
            return value != null && value.expiresAt() > nanos.get() ? Optional.of(value.value()) : Optional.empty();
        }
        public void put(String key, String value, Duration ttl) {
            if (failPut) { failPut = false; throw new IllegalStateException("simulated put failure"); }

            entries.put(key, new Entry(value, nanos.get() + Math.max(Duration.ofSeconds(1).toNanos(), ttl.toNanos())));
        }
        public void delete(String key) {
            if (failDelete) { failDelete = false; throw new IllegalStateException("simulated delete failure"); }
            entries.remove(key);
        }
    }

    @Test
    void fallsBackFromL1ToL2AndTracksHits() {
        TieredCacheService cache = cache();
        cache.put("answer", new Value("ok"));

        assertEquals("ok", cache.get("answer", Value.class).orElseThrow().text());
        cache.clearLocal();
        assertEquals("ok", cache.get("answer", Value.class).orElseThrow().text());

        assertEquals(1, cache.stats().l1Hits());
        assertEquals(1, cache.stats().l2Hits());
    }

    @Test
    void invalidatesBothTiers() {
        TieredCacheService cache = cache();
        cache.put("answer", new Value("ok"));

        cache.invalidate("answer");
        cache.clearLocal();

        assertTrue(cache.get("answer", Value.class).isEmpty());
        assertEquals(1, cache.stats().invalidations());
    }

    private TieredCacheService cache() {
        return new TieredCacheService(
                new InMemoryDistributedCacheAdapter(), new ObjectMapper(),
                new TieredCacheProperties(
                        100, Duration.ofMinutes(1), Duration.ofMinutes(5),
                        "redis://localhost:6379", "test"
                )
        );
    }

    private record Value(String text) { }
}
