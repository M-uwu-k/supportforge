package com.supportforge.publicbenchmark;

import com.supportforge.benchmark.RetrievalBenchmarkEnvironment;

import java.time.Instant;
import java.util.List;

/** 带数据revision、Collection和模型版本的公共评测正式报告。 */
public record PublicBenchmarkReport(
        String datasetId,
        String datasetRevision,
        RetrievalBenchmarkEnvironment environment,
        String collection,
        String indexVersion,
        long indexedPoints,
        int k,
        int candidateK,
        List<PublicBenchmarkVariantReport> variants,
        Instant generatedAt
) {
    public PublicBenchmarkReport {
        variants = List.copyOf(variants);
    }
}

