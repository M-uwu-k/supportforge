package com.supportforge.tool;

import com.supportforge.knowledge.InMemoryKnowledgeBase;
import com.supportforge.knowledge.KnowledgeQueryRewriter;
import com.supportforge.knowledge.KnowledgeSearchResult;
import com.supportforge.knowledge.MultiQueryKnowledgeRetriever;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeSearchToolTest {

    private final KnowledgeSearchTool tool =
            new KnowledgeSearchTool(
                    new MultiQueryKnowledgeRetriever(
                            new InMemoryKnowledgeBase(),
                            new KnowledgeQueryRewriter(4),
                            Runnable::run
                    )
            );

    @Test
    void shouldSearchKnowledgeWithCategoryAndLimit() {
        Object data = tool.execute(
                Map.of(
                        "query", "登录失败401",
                        "category", "technical",
                        "limit", 2
                ),
                new ToolContext(
                        "tool-user",
                        "tool-conversation"
                )
        );

        assertTrue(data instanceof List<?>);

        @SuppressWarnings("unchecked")
        List<KnowledgeSearchResult> results =
                (List<KnowledgeSearchResult>) data;

        assertEquals("tech-login-401", results.getFirst().document().id());
        assertTrue(results.size() <= 2);
    }

    @Test
    void shouldRejectUnsupportedCategory() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(
                        Map.of(
                                "query", "退款",
                                "category", "UNKNOWN"
                        ),
                        null
                )
        );

        assertEquals("不支持的知识分类：UNKNOWN", exception.getMessage());
    }

    @Test
    void shouldUseRewrittenQueriesThroughRealToolEntry() {
        Object data = tool.execute(
                Map.of(
                        "query", "认证失效怎么办",
                        "category", "technical",
                        "limit", 3
                ),
                new ToolContext(
                        "rewrite-user",
                        "rewrite-conversation"
                )
        );

        @SuppressWarnings("unchecked")
        List<KnowledgeSearchResult> results =
                (List<KnowledgeSearchResult>) data;

        assertTrue(
                results.stream()
                        .anyMatch(result ->
                                result.document()
                                        .id()
                                        .equals("tech-login-401")
                        )
        );
    }
}
