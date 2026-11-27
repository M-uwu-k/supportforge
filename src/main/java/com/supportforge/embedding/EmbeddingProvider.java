package com.supportforge.embedding;

import java.util.List;

/** Qwen3 Embedding端口；调用方不依赖云厂商SDK或HTTP响应Map。 */
public interface EmbeddingProvider {

    EmbeddingBatch embed(
            List<String> texts,
            EmbeddingPurpose purpose
    );

    default EmbeddingBatch embedQuery(String query) {
        return embed(List.of(query), EmbeddingPurpose.QUERY);
    }

    default EmbeddingBatch embedDocuments(List<String> documents) {
        return embed(documents, EmbeddingPurpose.DOCUMENT);
    }
}
