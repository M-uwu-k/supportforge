package com.supportforge.evaluation;

import java.util.Map;


public record EvalResult(

        String testId,

        boolean passed,

        Map<String, Double> scores,

        String detail,

        Map<String, Object> metadata
) {

    public EvalResult {

        scores = scores == null
                ? Map.of()
                : Map.copyOf(scores);


        detail = detail == null
                ? ""
                : detail;


        metadata = metadata == null
                ? Map.of()
                : Map.copyOf(metadata);
    }
}
