package com.supportforge.evaluation;


public record IntentCaseResult(

        String message,

        String expected,

        String predicted,

        boolean correct,

        double confidence,

        String reasoning
) {
}
