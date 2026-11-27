package com.supportforge.benchmark;

import com.supportforge.retrieval.RetrievalMode;

/** 消融实验的四种固定配置，避免运行时自由组合产生不可比报告。 */
public enum RetrievalBenchmarkVariant {
    DENSE_ONLY(RetrievalMode.DENSE, false),
    BM25_ONLY(RetrievalMode.BM25, false),
    HYBRID_RRF(RetrievalMode.HYBRID_RRF, false),
    HYBRID_RERANKER(RetrievalMode.HYBRID_RRF, true);

    private final RetrievalMode retrievalMode;
    private final boolean reranker;

    RetrievalBenchmarkVariant(RetrievalMode retrievalMode, boolean reranker) {
        this.retrievalMode = retrievalMode;
        this.reranker = reranker;
    }

    public RetrievalMode retrievalMode() {
        return retrievalMode;
    }

    public boolean usesReranker() {
        return reranker;
    }
}

