package com.supportforge.evaluation;


public record QualityScores(

        double relevance,

        double accuracy,

        double completeness,

        double helpfulness,

        boolean judgeFailed,

        String error
) {


    public QualityScores {


        relevance = clamp(relevance);
        accuracy = clamp(accuracy);
        completeness = clamp(completeness);
        helpfulness = clamp(helpfulness);


        error = error == null || error.isBlank()
                ? null
                : error.strip();
    }


    public double overall() {

        return round4(
                (
                        relevance
                                + accuracy
                                + completeness
                                + helpfulness
                ) / 4.0
        );
    }


    public static QualityScores failure(
            String error
    ) {


        return new QualityScores(
                0.5,
                0.5,
                0.5,
                0.5,
                true,
                error
        );
    }


    private static double clamp(double value) {

        if (!Double.isFinite(value)) {
            return 0.5;
        }


        return Math.max(
                0.0,
                Math.min(value, 1.0)
        );
    }


    private static double round4(double value) {
        return Math.round(value * 10_000.0)
                / 10_000.0;
    }
}
