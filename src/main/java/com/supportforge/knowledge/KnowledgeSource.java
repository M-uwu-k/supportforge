package com.supportforge.knowledge;

import java.util.List;

public record KnowledgeSource(
        String id,
        String title,
        KnowledgeCategory category,
        double score,
        List<String> matchedKeywords
) {

    public KnowledgeSource {
        matchedKeywords =
                List.copyOf(matchedKeywords);
    }
}