package com.supportforge.evaluation;

import java.util.List;
import java.util.Map;


public record IntentEvaluationReport(

        double accuracy,

        double macroF1,

        int total,

        int correct,

        Map<String, IntentClassMetrics> perClass,

        List<IntentCaseResult> cases
) {

    public IntentEvaluationReport {

        perClass = perClass == null
                ? Map.of()
                : Map.copyOf(perClass);


        cases = cases == null
                ? List.of()
                : List.copyOf(cases);

    }
}
