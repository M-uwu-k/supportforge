package com.supportforge.knowledge;

import java.util.List;


public record KnowledgeSearchResult(
        KnowledgeDocument document,
        double score,
        List<String> matchedKeywords
) {

    public KnowledgeSearchResult {

        matchedKeywords =
                List.copyOf(matchedKeywords);
    }
}
