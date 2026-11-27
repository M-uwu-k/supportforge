package com.supportforge.embedding;

import java.util.List;

/** 一次或多次底层批请求合并后的统一结果。 */
public record EmbeddingBatch(
        String model,
        String modelVersion,
        int dimensions,
        List<EmbeddingVector> vectors,
        int inputTokens
) {

    public EmbeddingBatch {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Embedding模型名不能为空");
        }
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("Embedding模型版本不能为空");
        }
        if (dimensions < 1) {
            throw new IllegalArgumentException("Embedding维度必须为正数");
        }
        vectors = List.copyOf(vectors);
        if (vectors.isEmpty()) {
            throw new IllegalArgumentException("Embedding结果不能为空");
        }
        for (int index = 0; index < vectors.size(); index++) {
            EmbeddingVector vector = vectors.get(index);
            if (vector.index() != index
                    || vector.values().size() != dimensions) {
                throw new IllegalArgumentException(
                        "Embedding结果序号或维度不一致"
                );
            }
        }
        if (inputTokens < 0) {
            throw new IllegalArgumentException("输入token数不能为负数");
        }
    }
}
