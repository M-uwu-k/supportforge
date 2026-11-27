package com.supportforge.knowledge;

import java.util.List;


public record KnowledgeDocument(
        String id,
        String title,
        KnowledgeCategory category,
        String content,
        List<String> keywords
) {


    public KnowledgeDocument {
        keywords = List.copyOf(keywords);
    }
}
