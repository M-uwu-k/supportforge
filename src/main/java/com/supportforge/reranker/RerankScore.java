package com.supportforge.reranker;

/** 一个输入文档的相关度分数；index对应调用方原候选位置。 */
public record RerankScore(int index, double score) {
    public RerankScore {
        if (index < 0 || !Double.isFinite(score) || score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("Reranker序号或分数非法");
        }
    }
}
