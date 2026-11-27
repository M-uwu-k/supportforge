package com.supportforge.publicbenchmark;

import com.supportforge.benchmark.RetrievalMissType;

/** 公共Benchmark逐查询结果；不保存Top100全文，避免报告无界膨胀。 */
public record PublicBenchmarkCaseResult(
        String id,
        RetrievalMissType missType,
        int firstCandidateRelevantRank,
        int firstRelevantRank,
        double candidateRecall,
        double recallAtK,
        double reciprocalRankAtK,
        double ndcgAtK,
        double averagePrecisionAtK,
        double latencyMs,
        boolean rerankerApplied
) { }

