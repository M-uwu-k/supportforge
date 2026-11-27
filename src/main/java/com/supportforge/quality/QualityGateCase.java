package com.supportforge.quality;

import java.util.Set;
import java.util.UUID;

/** 一条可版本化质量样本，同时描述检索期望、准入期望和最终回答引用。 */
public record QualityGateCase(
        String id,
        String query,
        Set<String> categories,
        Set<UUID> expectedPointIds,
        boolean expectedAllowed,
        String answer,
        Set<UUID> citedPointIds
) {
    public QualityGateCase {
        if (id == null || id.isBlank() || query == null || query.isBlank()) {
            throw new IllegalArgumentException("质量样本id和query不能为空");
        }
        categories = categories == null ? Set.of() : Set.copyOf(categories);
        expectedPointIds = expectedPointIds == null
                ? Set.of() : Set.copyOf(expectedPointIds);
        citedPointIds = citedPointIds == null ? Set.of() : Set.copyOf(citedPointIds);
        answer = answer == null ? "" : answer;
        if (!expectedAllowed && !citedPointIds.isEmpty()) {
            throw new IllegalArgumentException("期望拒答的样本不能声明引用");
        }
    }
}
