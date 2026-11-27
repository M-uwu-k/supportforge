package com.supportforge.knowledge;

import com.supportforge.tool.ToolContext;
import com.supportforge.tool.ToolManager;
import com.supportforge.tool.ToolResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Component
public class KnowledgeContextBuilder {


    private final ToolManager toolManager;


    private final KnowledgeBase directKnowledgeBase;


    @Autowired
    public KnowledgeContextBuilder(
            ToolManager toolManager
    ) {

        this.toolManager = toolManager;
        this.directKnowledgeBase = null;
    }


    public KnowledgeContextBuilder(
            KnowledgeBase knowledgeBase
    ) {


        this.toolManager = null;
        this.directKnowledgeBase = knowledgeBase;
    }


    public KnowledgeContext retrieve(
            String query,
            KnowledgeCategory category,
            int limit
    ) {

        return retrieve(
                query,
                category,
                limit,
                new ToolContext("", "")
        );
    }


    public KnowledgeContext retrieve(
            String query,
            KnowledgeCategory category,
            int limit,
            ToolContext context
    ) {


        if (query == null || query.isBlank()) {
            return emptyContext();
        }


        List<KnowledgeSearchResult> results;


        if (toolManager != null) {
            results = searchThroughToolManager(
                    query,
                    category,
                    limit,
                    context
            );
        } else {


            results = directKnowledgeBase.search(
                    query,
                    category,
                    limit
            );
        }


        return buildContext(results);
    }


    private List<KnowledgeSearchResult> searchThroughToolManager(
            String query,
            KnowledgeCategory category,
            int limit,
            ToolContext context
    ) {


        Map<String, Object> parameters =
                new LinkedHashMap<>();

        parameters.put("query", query);
        parameters.put("limit", limit);


        if (category != null) {
            parameters.put(
                    "category",
                    category.name()
            );
        }


        ToolResult toolResult =
                toolManager.call(
                        "knowledge_search",
                        parameters,
                        context
                );


        if (!toolResult.success()) {
            return List.of();
        }


        if (!(toolResult.data()
                instanceof List<?> rawResults)) {
            return List.of();
        }


        boolean containsInvalidResult =
                rawResults.stream()
                        .anyMatch(item ->
                                !(item
                                        instanceof KnowledgeSearchResult)
                        );


        if (containsInvalidResult) {
            return List.of();
        }


        return rawResults.stream()
                .map(KnowledgeSearchResult.class::cast)
                .toList();
    }


    private KnowledgeContext buildContext(
            List<KnowledgeSearchResult> results
    ) {

        if (results == null || results.isEmpty()) {
            return emptyContext();
        }


        List<KnowledgeSource> sources =
                results.stream()
                        .map(result ->
                                new KnowledgeSource(
                                        result.document().id(),
                                        result.document().title(),
                                        result.document().category(),
                                        result.score(),
                                        result.matchedKeywords()
                                )
                        )
                        .toList();


        StringBuilder builder =
                new StringBuilder();


        for (int index = 0;
             index < results.size();
             index++) {


            KnowledgeSearchResult result =
                    results.get(index);

            KnowledgeDocument document =
                    result.document();


            builder.append("[知识")
                    .append(index + 1)
                    .append("]\n");


            builder.append("来源编号：")
                    .append(document.id())
                    .append("\n");


            builder.append("标题：")
                    .append(document.title())
                    .append("\n");


            builder.append("分类：")
                    .append(document.category())
                    .append("\n");


            builder.append("命中关键词：")
                    .append(
                            String.join(
                                    "、",
                                    result.matchedKeywords()
                            )
                    )
                    .append("\n");


            builder.append("内容：\n")
                    .append(document.content().strip())
                    .append("\n\n");
        }


        return new KnowledgeContext(
                builder.toString().strip(),
                sources
        );
    }


    private KnowledgeContext emptyContext() {
        return new KnowledgeContext(
                "",
                List.of()
        );
    }


    public String build(
            String query,
            KnowledgeCategory category,
            int limit
    ) {
        return retrieve(
                query,
                category,
                limit
        ).promptText();
    }
}


