package com.supportforge.knowledge;

import java.util.List;


public record KnowledgeImportResult(
        int total,
        int imported,
        int failed,
        List<KnowledgeDocument> documents,
        List<String> errors
) {
    public KnowledgeImportResult {


        documents = List.copyOf(documents);
        errors = List.copyOf(errors);
    }
}
