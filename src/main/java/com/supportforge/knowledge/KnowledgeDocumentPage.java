package com.supportforge.knowledge;

import java.util.List;

/** 文档浏览页；totalElements 是分类过滤后、当前身份有权读取的数量。页码从 1 开始。 */
public record KnowledgeDocumentPage(
        List<KnowledgeDocument> documents,
        long totalElements,
        int page,
        int size,
        int totalPages
) {
    public KnowledgeDocumentPage {
        documents = List.copyOf(documents);
    }
}
