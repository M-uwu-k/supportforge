package com.supportforge.quality;

public record QualityGateThresholds(
        double recallAtK,
        double decisionAccuracy,
        double groundedAnswerRate,
        double citationPrecision,
        double refusalAccuracy
) {
    public static QualityGateThresholds from(QualityGateProperties properties) {
        return new QualityGateThresholds(
                properties.recallAtK(), properties.decisionAccuracy(),
                properties.groundedAnswerRate(), properties.citationPrecision(),
                properties.refusalAccuracy()
        );
    }
}
