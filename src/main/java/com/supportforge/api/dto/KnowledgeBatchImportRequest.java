package com.supportforge.api.dto;

import com.supportforge.knowledge.KnowledgeCategory;
import com.supportforge.knowledge.KnowledgeImportItem;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;


public record KnowledgeBatchImportRequest(


        @NotEmpty(message = "documents 至少包含一篇知识")
        List<KnowledgeImportItem> documents,

        KnowledgeCategory defaultCategory
) {
}
