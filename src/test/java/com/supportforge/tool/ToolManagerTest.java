package com.supportforge.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolManagerTest {

    private final ExecutorService executorService =
            Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void closeExecutor() {
        executorService.close();
    }

    @Test
    void shouldRegisterListAndCallWhitelistedTool() {
        EchoTool tool = new EchoTool();
        ToolManager manager = manager(tool);

        assertEquals(1, manager.list().size());
        assertEquals("echo", manager.list().getFirst().name());

        ToolResult result = manager.call(
                " ECHO ",
                Map.of("text", "hello"),
                new ToolContext("user-1", "conversation-1")
        );

        assertTrue(result.success());
        assertEquals("hello:user-1", result.data());
        assertEquals("echo", result.toolName());
        assertTrue(result.latencyMs() >= 0);
        assertTrue(tool.executed.get());
    }

    @Test
    void shouldRejectMissingWrongTypeAndUnknownParametersWithoutExecution() {
        EchoTool tool = new EchoTool();
        ToolManager manager = manager(tool);

        ToolResult missing = manager.call(
                "echo",
                Map.of(),
                null
        );
        assertFalse(missing.success());
        assertTrue(missing.error().contains("缺少必需参数：text"));

        ToolResult wrongType = manager.call(
                "echo",
                Map.of("text", 123),
                null
        );
        assertFalse(wrongType.success());
        assertTrue(wrongType.error().contains("类型错误"));

        ToolResult unknownParameter = manager.call(
                "echo",
                Map.of(
                        "text", "hello",
                        "dangerous", true
                ),
                null
        );
        assertFalse(unknownParameter.success());
        assertTrue(unknownParameter.error().contains("不支持参数：dangerous"));

        assertFalse(tool.executed.get());
    }

    @Test
    void shouldRejectUnknownTool() {
        ToolManager manager = manager(new EchoTool());

        ToolResult result = manager.call(
                "system_command",
                Map.of(),
                null
        );

        assertFalse(result.success());
        assertEquals("工具不存在：system_command", result.error());
    }

    @Test
    void shouldStopWaitingWhenToolTimesOut() {
        AtomicBoolean interrupted = new AtomicBoolean();
        Tool slowTool = new Tool() {
            @Override
            public String name() {
                return "slow";
            }

            @Override
            public String description() {
                return "超时测试工具";
            }

            @Override
            public List<ToolParameter> parameters() {
                return List.of();
            }

            @Override
            public Object execute(
                    Map<String, Object> parameters,
                    ToolContext context
            ) {
                try {
                    Thread.sleep(5_000);
                    return "too-late";
                } catch (InterruptedException exception) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                    return "interrupted";
                }
            }

            @Override
            public Duration timeout() {
                return Duration.ofMillis(30);
            }
        };

        ToolResult result = manager(slowTool).call(
                "slow",
                Map.of(),
                null
        );

        assertFalse(result.success());
        assertEquals("工具执行超时", result.error());

        for (int index = 0;
             index < 20 && !interrupted.get();
             index++) {
            Thread.onSpinWait();
        }
    }

    @Test
    void shouldEvictOnlyTheSelectedToolCache() {
        CachedCountingTool firstTool =
                new CachedCountingTool("first");
        CachedCountingTool secondTool =
                new CachedCountingTool("second");
        ToolManager manager = manager(firstTool, secondTool);

        ToolResult firstOriginal = manager.call(
                "first", Map.of(), null
        );
        ToolResult secondOriginal = manager.call(
                "second", Map.of(), null
        );
        assertFalse(firstOriginal.cached());
        assertFalse(secondOriginal.cached());

        assertTrue(manager.call("first", Map.of(), null).cached());
        assertTrue(manager.call("second", Map.of(), null).cached());
        assertEquals(1, firstTool.executions.get());
        assertEquals(1, secondTool.executions.get());

        assertEquals(1, manager.evictCache(" FIRST "));
        assertEquals(0, manager.evictCache("unknown"));
        assertEquals(0, manager.evictCache(null));

        assertFalse(manager.call("first", Map.of(), null).cached());
        assertTrue(manager.call("second", Map.of(), null).cached());
        assertEquals(2, firstTool.executions.get());
        assertEquals(1, secondTool.executions.get());
    }

    private ToolManager manager(Tool... tools) {
        return new ToolManager(
                List.of(tools),
                executorService
        );
    }

    private static final class EchoTool
            implements Tool {

        private final AtomicBoolean executed =
                new AtomicBoolean();

        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "返回输入内容";
        }

        @Override
        public List<ToolParameter> parameters() {
            return List.of(
                    new ToolParameter(
                            "text",
                            ToolValueType.STRING,
                            true,
                            "要返回的文字"
                    )
            );
        }

        @Override
        public Object execute(
                Map<String, Object> parameters,
                ToolContext context
        ) {
            executed.set(true);

            return parameters.get("text")
                    + ":"
                    + context.userId();
        }
    }

    private static final class CachedCountingTool
            implements Tool {

        private final String name;
        private final AtomicInteger executions =
                new AtomicInteger();

        private CachedCountingTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "缓存清理测试工具";
        }

        @Override
        public List<ToolParameter> parameters() {
            return List.of();
        }

        @Override
        public Object execute(
                Map<String, Object> parameters,
                ToolContext context
        ) {
            return name + "-" + executions.incrementAndGet();
        }

        @Override
        public Duration cacheTtl() {
            return Duration.ofMinutes(1);
        }

        @Override
        public boolean cachePerContext() {
            return false;
        }
    }
}
