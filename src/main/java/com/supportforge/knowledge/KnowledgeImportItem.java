package com.supportforge.knowledge;

import java.util.List;
import java.util.Objects;


public record KnowledgeImportItem(
        String id,
        String title,
        KnowledgeCategory category,
        String content,
        List<String> keywords
) {
    public KnowledgeImportItem {

        if (keywords == null) {
            keywords = List.of();
        } else {


            keywords = keywords.stream()
                    .filter(Objects::nonNull)
                    .map(String::strip)
                    .filter(keyword ->
                            !keyword.isBlank()
                    )
                    .distinct()
                    .toList();
        }

    }
}
