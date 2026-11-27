package com.supportforge.benchmark;

/** 查询级错误归因：未进入候选池，或候选存在但未进入最终Top K。 */
public enum RetrievalMissType {
    NONE,
    CANDIDATE_MISS,
    RANKING_MISS
}

