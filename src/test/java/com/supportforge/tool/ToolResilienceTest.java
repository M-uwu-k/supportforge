package com.supportforge.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class ToolResilienceTest {

    private final ExecutorService executor =
            Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void closeExecutor() {
        executor.close();
    }

    @Test
    void shouldReuseCacheRegardlessOfParameterInsertionOrder() {
        AtomicInteger executions = new AtomicInteger();
        TestTool tool = new TestTool((parameters, context) -> executions.incrementAndGet());
        ToolManager manager = manager(tool);
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("query", "refund");
        first.put("limit", 3);
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("limit", 3);
        reordered.put("query", "refund");

        ToolResult original = manager.call("test", first, null);
        ToolResult cached = manager.call("test", reordered, null);

        assertTrue(original.success());
        assertFalse(original.cached());
        assertTrue(cached.success());
        assertTrue(cached.cached());
        assertEquals(original.data(), cached.data());
        assertEquals(1, executions.get());
        ToolStatsSnapshot stats = manager.stats().get("test");
        assertEquals(2, stats.total());
        assertEquals(2, stats.successful());
        assertEquals(1, stats.cacheHits());
        assertEquals(0, stats.failed());
        assertEquals(1.0, stats.successRate());
    }

    @Test
    void shouldIsolateDefaultCacheByBothUserAndConversation() {
        AtomicInteger executions = new AtomicInteger();
        TestTool tool = new TestTool((parameters, context) -> executions.incrementAndGet());
        ToolManager manager = manager(tool);

        assertFalse(call(manager, "a", "conversation-1").cached());
        assertTrue(call(manager, "a", "conversation-1").cached());
        assertFalse(call(manager, "b", "conversation-1").cached());
        assertFalse(call(manager, "a", "conversation-2").cached());
        assertEquals(3, executions.get());
    }

    @Test
    void shouldSharePublicToolCacheAcrossContexts() {
        AtomicInteger executions = new AtomicInteger();
        TestTool tool = new TestTool((parameters, context) -> executions.incrementAndGet());
        tool.perContext = false;
        ToolManager manager = manager(tool);

        assertFalse(call(manager, "a", "conversation-1").cached());
        assertTrue(call(manager, "b", "conversation-2").cached());
        assertEquals(1, executions.get());
    }

    @Test
    void shouldExecuteAgainAfterTtlExpires() throws InterruptedException {
        AtomicInteger executions = new AtomicInteger();
        TestTool tool = new TestTool((parameters, context) -> executions.incrementAndGet());
        tool.ttl = Duration.ofMillis(50);
        ToolManager manager = manager(tool);

        ToolResult original = call(manager, "a", "1");
        Thread.sleep(100);
        ToolResult refreshed = call(manager, "a", "1");

        assertTrue(refreshed.success());
        assertFalse(refreshed.cached());
        assertNotEquals(original.data(), refreshed.data());
        assertEquals(2, executions.get());
    }

    @Test
    void shouldCountInvalidParametersWithoutOpeningCircuit() {
        AtomicInteger executions = new AtomicInteger();
        TestTool tool = new TestTool((parameters, context) -> executions.incrementAndGet());
        tool.threshold = 1;
        ToolManager manager = manager(tool);

        for (int index = 0; index < 5; index++) {
            assertFalse(manager.call("test", Map.of(), null).success());
        }

        ToolStatsSnapshot stats = manager.stats().get("test");
        assertEquals(5, stats.failed());
        assertEquals(0, stats.consecutiveFailures());
        assertEquals(CircuitState.CLOSED, stats.circuitState());
        assertEquals(0, executions.get());
        assertTrue(call(manager, "a", "1").success());
    }

    @Test
    void shouldRejectCallsAfterConsecutiveExecutionFailures() {
        AtomicInteger executions = new AtomicInteger();
        TestTool tool = new TestTool((parameters, context) -> {
            executions.incrementAndGet();
            throw new IllegalStateException("backend unavailable");
        });
        tool.threshold = 2;
        ToolManager manager = manager(tool);

        assertFalse(call(manager, "a", "1").success());
        assertFalse(call(manager, "a", "1").success());
        ToolResult rejected = call(manager, "a", "1");

        assertFalse(rejected.success());
        assertEquals("工具熔断中，请稍后重试", rejected.error());
        assertEquals(2, executions.get());
        ToolStatsSnapshot stats = manager.stats().get("test");
        assertEquals(3, stats.total());
        assertEquals(3, stats.failed());
        assertEquals(1, stats.circuitRejected());
        assertEquals(2, stats.consecutiveFailures());
        assertEquals(CircuitState.OPEN, stats.circuitState());
    }

    @Test
    void shouldRecoverAfterSuccessfulHalfOpenProbe() {
        AtomicInteger executions = new AtomicInteger();
        TestTool tool = new TestTool((parameters, context) -> {
            if (executions.incrementAndGet() == 1) {
                throw new IllegalStateException("first call fails");
            }
            return "recovered";
        });
        tool.threshold = 1;
        tool.recovery = Duration.ofNanos(1);
        ToolManager manager = manager(tool);

        assertFalse(call(manager, "a", "1").success());
        assertEquals(CircuitState.OPEN, manager.stats().get("test").circuitState());
        assertTrue(call(manager, "a", "1").success());
        assertEquals(CircuitState.CLOSED, manager.stats().get("test").circuitState());
        assertEquals(0, manager.stats().get("test").consecutiveFailures());
    }

    @Test
    void shouldAllowOnlyOneHalfOpenProbeAndReopenOnProbeFailure() {
        CircuitBreaker breaker = new CircuitBreaker(1, Duration.ofNanos(1));
        breaker.recordFailure();

        assertTrue(breaker.allowCall());
        assertEquals(CircuitState.HALF_OPEN, breaker.state());
        assertFalse(breaker.allowCall());

        breaker.recordFailure();
        assertEquals(CircuitState.OPEN, breaker.state());
        assertTrue(breaker.allowCall());
        breaker.recordSuccess();
        assertEquals(CircuitState.CLOSED, breaker.state());
    }

    @Test
    void shouldServeCachedResultWhileCircuitIsOpen() {
        AtomicInteger executions = new AtomicInteger();
        TestTool tool = new TestTool((parameters, context) -> {
            executions.incrementAndGet();
            if (parameters.get("query").equals("bad")) {
                throw new IllegalStateException("backend unavailable");
            }
            return "cached answer";
        });
        tool.threshold = 1;
        ToolManager manager = manager(tool);

        assertTrue(call(manager, "a", "1").success());
        assertFalse(manager.call("test", Map.of("query", "bad"),
                new ToolContext("a", "1")).success());
        assertEquals(CircuitState.OPEN, manager.stats().get("test").circuitState());
        assertTrue(call(manager, "a", "1").cached());
        assertEquals(CircuitState.OPEN, manager.stats().get("test").circuitState());
        assertEquals(2, executions.get());
    }

    @Test
    void shouldReleaseHalfOpenProbeWhenWaitingCallerIsInterrupted() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        TestTool tool = new TestTool((parameters, context) -> {
            if (executions.incrementAndGet() == 1) {
                throw new IllegalStateException("first call fails");
            }
            probeStarted.countDown();
            try {
                releaseProbe.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return "probe result";
        });
        tool.threshold = 1;
        tool.recovery = Duration.ofNanos(1);
        ToolManager manager = manager(tool);
        assertFalse(call(manager, "a", "1").success());

        AtomicReference<ToolResult> interruptedResult = new AtomicReference<>();
        Thread caller = Thread.startVirtualThread(() ->
                interruptedResult.set(call(manager, "a", "1")));
        try {
            assertTrue(probeStarted.await(2, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(2_000);
            assertFalse(caller.isAlive());
            assertFalse(interruptedResult.get().success());
            assertNotEquals(CircuitState.HALF_OPEN,
                    manager.stats().get("test").circuitState(),
                    "等待线程中断后，不能永久占用半开探测名额");
        } finally {
            releaseProbe.countDown();
            caller.interrupt();
            caller.join(2_000);
        }
    }

    private ToolManager manager(Tool tool) {
        return new ToolManager(List.of(tool), executor);
    }

    @Test
    void serverIdentitySeparatesActorsTenantsAndRolesButNotRequestIds() {
        AtomicInteger executions = new AtomicInteger();
        TestTool tool = new TestTool((parameters, context) -> executions.incrementAndGet());
        tool.perContext = false;
        ToolManager manager = manager(tool);
        java.util.UUID tenant = java.util.UUID.randomUUID();
        var admin = new com.supportforge.security.RequestIdentity(tenant, "same-actor",
                java.util.Set.of(com.supportforge.security.EnterpriseRole.ADMIN), "r1");
        var same = new com.supportforge.security.RequestIdentity(tenant, "same-actor", admin.roles(), "r2");
        var agent = new com.supportforge.security.RequestIdentity(tenant, "same-actor",
                java.util.Set.of(com.supportforge.security.EnterpriseRole.AGENT), "r3");
        var otherActor = new com.supportforge.security.RequestIdentity(tenant, "another-actor", admin.roles(), "r4");
        var otherTenant = new com.supportforge.security.RequestIdentity(java.util.UUID.randomUUID(), "same-actor", admin.roles(), "r5");
        assertFalse(com.supportforge.security.TenantContext.callWith(admin, () -> call(manager, "u", "c")).cached());
        assertTrue(com.supportforge.security.TenantContext.callWith(same, () -> call(manager, "u2", "c2")).cached());
        assertFalse(com.supportforge.security.TenantContext.callWith(agent, () -> call(manager, "u", "c")).cached());
        assertFalse(com.supportforge.security.TenantContext.callWith(otherActor, () -> call(manager, "u", "c")).cached());
        assertFalse(com.supportforge.security.TenantContext.callWith(otherTenant, () -> call(manager, "u", "c")).cached());
        assertEquals(4, executions.get());
    }

    private ToolResult call(ToolManager manager, String user, String conversation) {
        return manager.call("test", Map.of("query", "refund"),
                new ToolContext(user, conversation));
    }

    private static final class TestTool implements Tool {
        private final BiFunction<Map<String, Object>, ToolContext, Object> handler;
        private Duration ttl = Duration.ofMinutes(1);
        private Duration recovery = Duration.ofMinutes(1);
        private boolean perContext = true;
        private int threshold = 3;

        private TestTool(BiFunction<Map<String, Object>, ToolContext, Object> handler) {
            this.handler = handler;
        }

        @Override public String name() { return "test"; }
        @Override public String description() { return "resilience test tool"; }
        @Override public List<ToolParameter> parameters() {
            return List.of(
                    new ToolParameter("query", ToolValueType.STRING, true, "query"),
                    new ToolParameter("limit", ToolValueType.INTEGER, false, "limit"));
        }
        @Override public Object execute(Map<String, Object> parameters, ToolContext context) {
            return handler.apply(parameters, context);
        }
        @Override public Duration cacheTtl() { return ttl; }
        @Override public boolean cachePerContext() { return perContext; }
        @Override public int failureThreshold() { return threshold; }
        @Override public Duration recoveryDuration() { return recovery; }
    }
}
