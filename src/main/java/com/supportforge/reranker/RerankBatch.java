package com.supportforge.reranker;

import java.util.List;

/** 一次逻辑精排的完整结果和模型身份。 */
public record RerankBatch(
        String model,
        String modelVersion,
        List<RerankScore> scores
) {
    public RerankBatch {
        if (model == null || model.isBlank()
                || modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("Reranker模型身份不能为空");
        }
        scores = List.copyOf(scores);
    }
}
