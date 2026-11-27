package com.supportforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 企业混合召回参数。候选深度和最终返回数量分离，给后续Reranker保留足够候选。
 */
@ConfigurationProperties(prefix = "supportforge.retrieval")
public record HybridRetrievalProperties(
        int candidateLimit,
        int defaultLimit,
        int maximumLimit,
        int rrfK
) {

    public HybridRetrievalProperties {
        candidateLimit = candidateLimit == 0 ? 100 : candidateLimit;
        defaultLimit = defaultLimit == 0 ? 8 : defaultLimit;
        maximumLimit = maximumLimit == 0 ? 50 : maximumLimit;
        rrfK = rrfK == 0 ? 60 : rrfK;
        if (candidateLimit < 1 || candidateLimit > 1_000) {
            throw new IllegalArgumentException("混合召回候选数必须在1到1000之间");
        }
        if (defaultLimit < 1 || maximumLimit < defaultLimit
                || maximumLimit > candidateLimit) {
            throw new IllegalArgumentException(
                    "默认返回数必须为正，且不能超过最大返回数和候选数"
            );
        }
        if (rrfK < 1 || rrfK > 10_000) {
            throw new IllegalArgumentException("RRF常数必须在1到10000之间");
        }
    }

    public int normalizeLimit(Integer requested) {
        if (requested == null) {
            return defaultLimit;
        }
        if (requested < 1 || requested > maximumLimit) {
            throw new IllegalArgumentException(
                    "检索limit必须在1到" + maximumLimit + "之间"
            );
        }
        return requested;
    }
}
