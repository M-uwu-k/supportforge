package com.supportforge.feedback;

import java.util.List;


public record FeedbackSnapshot(
        List<AnswerRecord> answers,
        List<AnswerFeedback> feedbacks
) {


    public FeedbackSnapshot {
        answers = answers == null
                ? List.of()
                : List.copyOf(answers);

        feedbacks = feedbacks == null
                ? List.of()
                : List.copyOf(feedbacks);
    }


    public static FeedbackSnapshot empty() {
        return new FeedbackSnapshot(
                List.of(),
                List.of()
        );
    }
}
