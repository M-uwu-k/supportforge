package com.supportforge.quality;

import java.util.List;

public record QualityGateRequest(List<QualityGateCase> cases) {
    public QualityGateRequest {
        cases = cases == null ? List.of() : List.copyOf(cases);
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("质量门禁至少需要一条样本");
        }
        long distinctIds = cases.stream().map(QualityGateCase::id).distinct().count();
        if (distinctIds != cases.size()) {
            throw new IllegalArgumentException("质量样本id不能重复");
        }
    }
}
