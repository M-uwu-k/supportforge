package com.supportforge.embedding;

import com.supportforge.knowledge.ingestion.KnowledgeChunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 把第7步chunk批量转换为第9步可索引对象。 */
@Service
public class KnowledgeEmbeddingService {

    private final EmbeddingProvider provider;

    public KnowledgeEmbeddingService(EmbeddingProvider provider) {
        this.provider = provider;
    }

    public List<EmbeddedKnowledgeChunk> embed(
            List<KnowledgeChunk> chunks
    ) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        EmbeddingBatch batch = provider.embedDocuments(
                chunks.stream()
                        .map(KnowledgeChunk::content)
                        .toList()
        );
        if (batch.vectors().size() != chunks.size()) {
            throw new EmbeddingProviderException(
                    "Embedding结果与知识分块数量不一致"
            );
        }
        List<EmbeddedKnowledgeChunk> result = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            result.add(new EmbeddedKnowledgeChunk(
                    chunks.get(index),
                    batch.vectors().get(index).values(),
                    batch.model(),
                    batch.modelVersion(),
                    batch.dimensions()
            ));
        }
        return List.copyOf(result);
    }
}
