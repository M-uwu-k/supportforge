package com.supportforge.retrieval;

/** 第一阶段候选召回方式；线上默认仍使用HYBRID_RRF。 */
public enum RetrievalMode {
    DENSE,
    BM25,
    HYBRID_RRF
}

