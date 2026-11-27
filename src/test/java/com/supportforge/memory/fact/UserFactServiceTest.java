package com.supportforge.memory.fact;

import com.supportforge.cache.InMemoryDistributedCacheAdapter;
import com.supportforge.cache.TieredCacheProperties;
import com.supportforge.cache.TieredCacheService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import com.supportforge.security.TenantContextProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

class UserFactServiceTest {

    @Test
    void correctionWaitsForReadAndFillBeforeInvalidatingOldSnapshot() throws Exception {
        verifyMutationDuringRead(false);
    }

    @Test
    void revocationWaitsForReadAndFillBeforeInvalidatingOldSnapshot() throws Exception {
        verifyMutationDuringRead(true);
    }

    private void verifyMutationDuringRead(boolean revoke) throws Exception {
        var readStarted = new java.util.concurrent.CountDownLatch(1);
        var releaseRead = new java.util.concurrent.CountDownLatch(1);
        var mutationStarted = new java.util.concurrent.CountDownLatch(1);
        var once = new java.util.concurrent.atomic.AtomicBoolean(true);
        var port = new InMemoryUserFactAdapter() {
            @Override public List<UserFact> findActive(java.util.UUID tenant, String user, Instant now) {
                List<UserFact> snapshot = super.findActive(tenant, user, now);
                if (once.compareAndSet(true, false)) {
                    readStarted.countDown();
                    try { if (!releaseRead.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("read timed out"); }
                    catch (InterruptedException exception) { throw new RuntimeException(exception); }
                }
                return snapshot;
            }
        };
        TieredCacheService cache = cache();
        UserFactService service = new UserFactService(port, cache,
                new TenantContextProvider("00000000-0000-0000-0000-000000000001"));
        service.put("u1", "style", "old", UserFactSource.USER_STATEMENT, "test", 1, null);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var reader = executor.submit(() -> service.active("u1"));
            assertTrue(readStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var mutation = executor.submit(() -> {
                mutationStarted.countDown();
                if (revoke) service.revoke("u1", "style");
                else service.correct("u1", "style", "new", "test-correction");
            });
            try {
                assertTrue(mutationStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> mutation.get(150, java.util.concurrent.TimeUnit.MILLISECONDS));
            } finally { releaseRead.countDown(); }
            reader.get(5, java.util.concurrent.TimeUnit.SECONDS);
            mutation.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally { releaseRead.countDown(); }
        if (revoke) assertTrue(service.active("u1").isEmpty());
        else assertEquals("new", service.active("u1").getFirst().value());
        cache.clearLocal();
        if (revoke) assertTrue(service.active("u1").isEmpty());
        else assertEquals("new", service.active("u1").getFirst().value());
    }

    @Test
    void correctionAndRevocationStayEffectiveWhenDistributedInvalidationFails() {
        var l2 = spy(new InMemoryDistributedCacheAdapter());
        var guard = new com.supportforge.resilience.DependencyResilienceGuard(
                new com.supportforge.resilience.ResilienceProperties(Duration.ofSeconds(2), 3,
                        Duration.ofSeconds(10), 50, 100, Duration.ofMinutes(1)));
        try {
            TieredCacheService cache = new TieredCacheService(l2, new ObjectMapper(),
                    new TieredCacheProperties(100, Duration.ofSeconds(30), Duration.ofMinutes(10),
                            "redis://unused:6379", "fault-test"), guard);
            UserFactService service = new UserFactService(new InMemoryUserFactAdapter(), cache,
                    new TenantContextProvider("00000000-0000-0000-0000-000000000001"));
            service.put("u1", "style", "简洁", UserFactSource.USER_STATEMENT, "message-1", 1, null);
            assertEquals("简洁", service.active("u1").getFirst().value());
            doThrow(new IllegalStateException("transient delete failure")).when(l2).delete(anyString());

            assertEquals("详细", service.correct("u1", "style", "详细", "ticket-1").value());
            assertEquals("详细", service.active("u1").getFirst().value());
            cache.clearLocal();
            assertEquals("详细", service.active("u1").getFirst().value());
            assertTrue(service.revoke("u1", "style"));
            assertTrue(service.active("u1").isEmpty());
            cache.clearLocal();
            assertTrue(service.active("u1").isEmpty());
        } finally { guard.close(); }
    }

    @Test
    void correctionSupersedesPreviousFact() {
        UserFactService service = new UserFactService(
                new InMemoryUserFactAdapter(),
                cache(),
                new TenantContextProvider("00000000-0000-0000-0000-000000000001")
        );
        UserFact first = service.put(
                "u1", "preferred_language", "英文",
                UserFactSource.USER_STATEMENT, "message-1", 1, null
        );

        UserFact corrected = service.correct(
                "u1", "preferred_language", "中文", "ticket-8"
        );

        assertEquals(first.id(), corrected.supersedesFactId());
        assertEquals("中文", service.active("u1").getFirst().value());
    }

    @Test
    void inferenceRequiresEvidenceReference() {
        UserFactService service = new UserFactService(
                new InMemoryUserFactAdapter(),
                cache(),
                new TenantContextProvider("00000000-0000-0000-0000-000000000001")
        );
        assertThrows(IllegalArgumentException.class, () -> service.put(
                "u1", "risk", "high", UserFactSource.AGENT_INFERENCE,
                "", 0.7, Instant.now().plusSeconds(60)
        ));
    }

    @Test
    void revokeRemovesFactFromActiveView() {
        UserFactService service = new UserFactService(
                new InMemoryUserFactAdapter(),
                cache(),
                new TenantContextProvider("00000000-0000-0000-0000-000000000001")
        );
        service.put("u1", "style", "简洁", UserFactSource.CRM, "crm-1", 0.9, null);

        assertTrue(service.revoke("u1", "style"));
        assertTrue(service.active("u1").isEmpty());
    }

    private TieredCacheService cache() {
        return new TieredCacheService(
                new InMemoryDistributedCacheAdapter(), new ObjectMapper(),
                new TieredCacheProperties(
                        100, Duration.ofSeconds(30), Duration.ofMinutes(10),
                        "redis://localhost:6379", "test"
                )
        );
    }

    @Test
    void expiredFactsDisappearFromBothCacheLayersWithoutWaitingForCacheTtl() {
        Instant start = Instant.now();
        java.time.Clock clock = org.mockito.Mockito.mock(java.time.Clock.class);
        org.mockito.Mockito.when(clock.instant()).thenReturn(start);
        TieredCacheService cache = cache();
        UserFactService service = new UserFactService(new InMemoryUserFactAdapter(), cache,
                new TenantContextProvider("00000000-0000-0000-0000-000000000001"), clock);
        service.put("expiry-user", "temporary", "valid briefly", UserFactSource.CRM,
                "test", 1, start.plusSeconds(60));
        service.put("expiry-user", "permanent", "still valid", UserFactSource.CRM,
                "test", 1, null);
        assertEquals(2, service.active("expiry-user").size());
        org.mockito.Mockito.when(clock.instant()).thenReturn(start.plusSeconds(60));
        assertEquals(List.of("permanent"), service.active("expiry-user").stream().map(UserFact::key).toList());
        cache.clearLocal();
        assertEquals(List.of("permanent"), service.active("expiry-user").stream().map(UserFact::key).toList());
        assertEquals(1, cache.stats().l2Hits());
    }
}
