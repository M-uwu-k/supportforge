package com.supportforge.feedback;


public class FeedbackTargetNotFoundException
        extends RuntimeException {

    public FeedbackTargetNotFoundException(
            String message
    ) {
        super(message);
    }
}
