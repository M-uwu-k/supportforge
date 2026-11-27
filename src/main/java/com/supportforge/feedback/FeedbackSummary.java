package com.supportforge.feedback;

import java.util.List;


public record FeedbackSummary(
        long total,
        long positive,
        long negative,
        double satisfactionRate,
        List<String> recentNegativeComments
) {


    public FeedbackSummary {
        recentNegativeComments =
                recentNegativeComments == null
                        ? List.of()
                        : List.copyOf(
                        recentNegativeComments
                );
    }
}
