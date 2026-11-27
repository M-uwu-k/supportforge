package com.supportforge.knowledge;

import com.supportforge.tool.Tool;
import com.supportforge.tool.ToolContext;
import com.supportforge.tool.ToolManager;
import com.supportforge.tool.ToolParameter;
import com.supportforge.tool.ToolStatsSnapshot;
import com.supportforge.tool.ToolValueType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeContextBuilderToolTest {

    private final ExecutorService executor =
            Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void closeExecutor() {
        executor.close();
    }

    @Test
    void shouldRetrieveKnowledgeThroughToolManagerAndReuseCache() {
        AtomicInteger executions = new AtomicInteger();
        AtomicReference<ToolContext> receivedContext =
                new AtomicReference<>();

        Tool tool = knowledgeTool((parameters, context) -> {
            executions.incrementAndGet();
            receivedContext.set(context);
            return List.of(result());
        });

        ToolManager manager =
                new ToolManager(List.of(tool), executor);
        KnowledgeContextBuilder builder =
                new KnowledgeContextBuilder(manager);

        KnowledgeContext first = builder.retrieve(
                "登录失败401",
                KnowledgeCategory.TECHNICAL,
                3,
                new ToolContext("user-a", "conversation-a")
        );
        KnowledgeContext second = builder.retrieve(
                "登录失败401",
                KnowledgeCategory.TECHNICAL,
                3,
                new ToolContext("user-b", "conversation-b")
        );

        assertTrue(first.hasKnowledge());
        assertTrue(first.promptText().contains("登录失败与错误码401"));
        assertEquals("tech-login-401", first.sources().getFirst().id());
        assertEquals(first, second);
        assertEquals(1, executions.get());
        assertEquals("user-a", receivedContext.get().userId());
        assertEquals("conversation-a", receivedContext.get().conversationId());

        ToolStatsSnapshot stats =
                manager.stats().get("knowledge_search");
        assertEquals(2, stats.total());
        assertEquals(2, stats.successful());
        assertEquals(1, stats.cacheHits());
    }

    @Test
    void shouldDegradeToEmptyKnowledgeWhenToolFails() {
        Tool failingTool = knowledgeTool((parameters, context) -> {
            throw new IllegalStateException("knowledge backend unavailable");
        });

        ToolManager manager =
                new ToolManager(List.of(failingTool), executor);
        KnowledgeContextBuilder builder =
                new KnowledgeContextBuilder(manager);

        KnowledgeContext context = builder.retrieve(
                "退款失败",
                KnowledgeCategory.BILLING,
                3,
                new ToolContext("user-a", "conversation-a")
        );

        assertFalse(context.hasKnowledge());
        assertEquals("", context.promptText());
        assertTrue(context.sources().isEmpty());
        assertEquals(1,
                manager.stats().get("knowledge_search").failed());
    }

    private Tool knowledgeTool(ToolHandler handler) {
        return new Tool() {
            @Override
            public String name() {
                return "knowledge_search";
            }

            @Override
            public String description() {
                return "测试知识检索工具";
            }

            @Override
            public List<ToolParameter> parameters() {
                return List.of(
                        new ToolParameter(
                                "query",
                                ToolValueType.STRING,
                                true,
                                "问题"
                        ),
                        new ToolParameter(
                                "category",
                                ToolValueType.STRING,
                                false,
                                "分类"
                        ),
                        new ToolParameter(
                                "limit",
                                ToolValueType.INTEGER,
                                false,
                                "数量"
                        )
                );
            }

            @Override
            public Object execute(
                    Map<String, Object> parameters,
                    ToolContext context
            ) {
                return handler.execute(parameters, context);
            }

            @Override
            public Duration cacheTtl() {
                return Duration.ofMinutes(1);
            }

            @Override
            public boolean cachePerContext() {
                return false;
            }
        };
    }

    private KnowledgeSearchResult result() {
        KnowledgeDocument document =
                new KnowledgeDocument(
                        "tech-login-401",
                        "登录失败与错误码401",
                        KnowledgeCategory.TECHNICAL,
                        "错误码401通常表示登录凭证无效。",
                        List.of("登录", "401")
                );

        return new KnowledgeSearchResult(
                document,
                0.95,
                List.of("登录", "401")
        );
    }

    @FunctionalInterface
    private interface ToolHandler {
        Object execute(
                Map<String, Object> parameters,
                ToolContext context
        );
    }
}
