package com.supportforge.quality;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record QualityGateCaseResult(
        String id,
        boolean expectedAllowed,
        boolean actualAllowed,
        boolean decisionCorrect,
        double recall,
        boolean answerGrounded,
        Set<UUID> retrievedPointIds,
        List<String> issues
) {
    public QualityGateCaseResult {
        retrievedPointIds = Set.copyOf(retrievedPointIds);
        issues = List.copyOf(issues);
    }
}
