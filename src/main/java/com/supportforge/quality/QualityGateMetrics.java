package com.supportforge.quality;

public record QualityGateMetrics(
        int cases,
        double recallAtK,
        double decisionAccuracy,
        double groundedAnswerRate,
        double citationPrecision,
        double refusalAccuracy
) { }
