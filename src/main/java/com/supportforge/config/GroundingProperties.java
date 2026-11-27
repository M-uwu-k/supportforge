package com.supportforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Grounding证据准入和回答边界参数。 */
@ConfigurationProperties(prefix = "supportforge.grounding")
public record GroundingProperties(
        int maximumEvidence,
        int minimumEvidence,
        double minimumRerankScore,
        double minimumQueryCoverage,
        String refusalMessage
) {
    public GroundingProperties {
        maximumEvidence = maximumEvidence == 0 ? 5 : maximumEvidence;
        minimumEvidence = minimumEvidence == 0 ? 1 : minimumEvidence;
        minimumRerankScore = minimumRerankScore == 0.0 ? 0.12 : minimumRerankScore;
        minimumQueryCoverage = minimumQueryCoverage == 0.0 ? 0.30 : minimumQueryCoverage;
        refusalMessage = refusalMessage == null || refusalMessage.isBlank()
                ? "当前知识库没有足够证据回答这个问题，请补充信息或转人工客服。"
                : refusalMessage.strip();
        if (maximumEvidence < 1 || maximumEvidence > 20
                || minimumEvidence < 1 || minimumEvidence > maximumEvidence) {
            throw new IllegalArgumentException("Grounding证据数量配置非法");
        }
        if (!probability(minimumRerankScore)
                || !probability(minimumQueryCoverage)) {
            throw new IllegalArgumentException("Grounding分数门槛必须在0到1之间");
        }
    }

    private static boolean probability(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }
}
