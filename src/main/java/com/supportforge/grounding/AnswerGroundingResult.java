package com.supportforge.grounding;

import java.util.List;

public record AnswerGroundingResult(boolean grounded, List<String> issues) {
    public AnswerGroundingResult {
        issues = List.copyOf(issues);
    }
}
