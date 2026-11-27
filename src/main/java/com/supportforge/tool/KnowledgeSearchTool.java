package com.supportforge.tool;

import com.supportforge.knowledge.MultiQueryKnowledgeRetriever;
import com.supportforge.knowledge.KnowledgeCategory;
import com.supportforge.knowledge.KnowledgeSearchResult;
import com.supportforge.security.ResourceAccessService;
import com.supportforge.security.AclPermission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;


@Component
public class KnowledgeSearchTool
        implements Tool {
    private ResourceAccessService access;

    @Autowired
    void configureAccess(ResourceAccessService access) { this.access = access; }


    private final MultiQueryKnowledgeRetriever
            knowledgeRetriever;


    public KnowledgeSearchTool(
            MultiQueryKnowledgeRetriever
                    knowledgeRetriever
    ) {
        this.knowledgeRetriever =
                knowledgeRetriever;
    }


    @Override
    public String name() {
        return "knowledge_search";
    }


    @Override
    public String description() {
        return "根据问题、分类和数量限制搜索知识库";
    }


    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                new ToolParameter(
                        "query",
                        ToolValueType.STRING,
                        true,
                        "要检索的用户问题"
                ),
                new ToolParameter(
                        "category",
                        ToolValueType.STRING,
                        false,
                        "可选分类：TECHNICAL、BILLING或GENERAL"
                ),
                new ToolParameter(
                        "limit",
                        ToolValueType.INTEGER,
                        false,
                        "最多返回的结果数量"
                )
        );
    }


    @Override
    public Object execute(
            Map<String, Object> parameters,
            ToolContext context
    ) {

        String query =
                ((String) parameters.get("query"))
                        .strip();


        if (query.isBlank()) {
            throw new IllegalArgumentException(
                    "query不能为空"
            );
        }


        KnowledgeCategory category =
                parseCategory(
                        parameters.get("category")
                );


        int limit =
                parameters.containsKey("limit")
                        ? ((Number) parameters.get("limit"))
                        .intValue()
                        : 3;


        return filterCachedResult(knowledgeRetriever.search(
                query,
                category,
                limit
        ), context);
    }

    @Override
    public Object filterCachedResult(Object data, ToolContext context) {
        if (!(data instanceof List<?> results)) throw new IllegalStateException("知识缓存格式错误");
        return results.stream().map(item -> {
            if (!(item instanceof KnowledgeSearchResult result)) throw new IllegalStateException("知识缓存项格式错误");
            return result;
        }).filter(result -> access == null || access.allowed(
                "knowledge_document", result.document().id(), AclPermission.READ)).toList();
    }


    @Override
    public Duration timeout() {
        return Duration.ofSeconds(2);
    }


    private KnowledgeCategory parseCategory(
            Object value
    ) {

        if (value == null) {
            return null;
        }

        String category =
                value.toString()
                        .strip();


        if (category.isBlank()) {
            return null;
        }


        try {
            return KnowledgeCategory.valueOf(
                    category.toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {

            throw new IllegalArgumentException(
                    "不支持的知识分类：" + category
            );
        }
    }


    @Override
    public Duration cacheTtl() {
        return Duration.ofSeconds(60);
    }


    @Override
    public boolean cachePerContext() {

        return false;
    }


    @Override
    public int failureThreshold() {
        return 3;
    }


    @Override
    public Duration recoveryDuration() {
        return Duration.ofSeconds(20);
    }
}
