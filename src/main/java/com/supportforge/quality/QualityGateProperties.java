package com.supportforge.quality;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 发布门禁阈值；任何一项低于阈值，整批样本都不允许通过。 */
@ConfigurationProperties("supportforge.quality-gate")
public record QualityGateProperties(
        @DefaultValue("0.85") double recallAtK,
        @DefaultValue("0.95") double decisionAccuracy,
        @DefaultValue("0.95") double groundedAnswerRate,
        @DefaultValue("0.98") double citationPrecision,
        @DefaultValue("0.98") double refusalAccuracy
) {
    public QualityGateProperties {
        validate("recall-at-k", recallAtK);
        validate("decision-accuracy", decisionAccuracy);
        validate("grounded-answer-rate", groundedAnswerRate);
        validate("citation-precision", citationPrecision);
        validate("refusal-accuracy", refusalAccuracy);
    }

    private static void validate(String name, double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(name + "必须在0到1之间");
        }
    }
}
