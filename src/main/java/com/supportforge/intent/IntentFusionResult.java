package com.supportforge.intent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;


public record IntentFusionResult(
        IntentCategory intent,
        double confidence,
        Map<String, Double> sourceScores,
        String reasoning
) {


    public IntentFusionResult {

        intent = intent == null
                ? IntentCategory.OTHER
                : intent;


        if (!Double.isFinite(confidence)) {
            confidence = 0.0;
        }

        confidence = Math.max(
                0.0,
                Math.min(1.0, confidence)
        );


        sourceScores = sourceScores == null
                ? Map.of()
                : Collections.unmodifiableMap(
                new LinkedHashMap<>(
                        sourceScores
                )
        );


        reasoning = reasoning == null
                ? ""
                : reasoning.strip();
    }
}
