package com.supportforge.embedding;

import com.supportforge.knowledge.ingestion.KnowledgeChunk;

import java.util.List;

/** 第9步写Qdrant所需的“事实chunk + 稠密向量 + 模型身份”。 */
public record EmbeddedKnowledgeChunk(
        KnowledgeChunk chunk,
        List<Float> denseVector,
        String model,
        String modelVersion,
        int dimensions
) {

    public EmbeddedKnowledgeChunk {
        if (chunk == null) {
            throw new IllegalArgumentException("知识分块不能为空");
        }
        denseVector = List.copyOf(denseVector);
        if (denseVector.size() != dimensions) {
            throw new IllegalArgumentException("分块向量维度不一致");
        }
    }
}
