package com.supportforge.publicbenchmark;

/** 标准公开检索指标；所有聚合均按query做Macro Average。 */
public record PublicBenchmarkMetrics(
        int queries,
        int candidateK,
        int k,
        double candidateHit,
        double candidateRecall,
        double hitAtK,
        double recallAtK,
        double mrrAtK,
        double ndcgAtK,
        double mapAtK,
        int successfulQueries,
        int candidateMisses,
        int rankingMisses,
        double p50LatencyMs,
        double p95LatencyMs,
        double p99LatencyMs
) { }

