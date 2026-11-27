package com.supportforge.quality;

import java.time.Instant;
import java.util.List;

public record QualityGateResult(
        boolean passed,
        QualityGateMetrics metrics,
        QualityGateThresholds thresholds,
        List<String> failedMetrics,
        List<QualityGateCaseResult> caseResults,
        Instant evaluatedAt
) {
    public QualityGateResult {
        failedMetrics = List.copyOf(failedMetrics);
        caseResults = List.copyOf(caseResults);
    }
}
