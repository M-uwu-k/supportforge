package com.supportforge.evaluation;

import java.util.List;
import java.util.Map;


public record EvalReport(

        String timestamp,

        int total,

        int passed,

        double passRate,

        Map<String, Double> avgScores,

        List<String> regressions,

        List<String> recommendations,

        List<EvalResult> results
) {


    public EvalReport {
        avgScores = avgScores == null
                ? Map.of()
                : Map.copyOf(avgScores);

        regressions = regressions == null
                ? List.of()
                : List.copyOf(regressions);

        recommendations =
                recommendations == null
                        ? List.of()
                        : List.copyOf(recommendations);


        results = results == null
                ? List.of()
                : List.copyOf(results);
    }
}
