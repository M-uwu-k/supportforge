package com.supportforge.publicbenchmark;

import com.supportforge.benchmark.RetrievalBenchmarkVariant;

import java.util.List;

/** 一种公开检索配置的指标、警告和逐题结果。 */
public record PublicBenchmarkVariantReport(
        RetrievalBenchmarkVariant variant,
        PublicBenchmarkMetrics metrics,
        List<String> warnings,
        List<PublicBenchmarkCaseResult> caseResults
) {
    public PublicBenchmarkVariantReport {
        warnings = List.copyOf(warnings);
        caseResults = List.copyOf(caseResults);
    }
}

