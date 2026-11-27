package com.supportforge.evaluation;


public record IntentClassMetrics(

        long truePositive,

        long falsePositive,

        long falseNegative,


        double precision,


        double recall,


        double f1
) {
}
