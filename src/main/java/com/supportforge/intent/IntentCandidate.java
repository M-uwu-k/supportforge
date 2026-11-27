package com.supportforge.intent;


public record IntentCandidate(
        IntentCategory intent,
        double confidence,
        String reasoning,
        boolean failed
) {


    public IntentCandidate {

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


        reasoning = reasoning == null
                ? ""
                : reasoning.strip();
    }


    public static IntentCandidate success(
            IntentCategory intent,
            double confidence,
            String reasoning
    ) {
        return new IntentCandidate(
                intent,
                confidence,
                reasoning,
                false
        );
    }


    public static IntentCandidate failure(
            String reasoning
    ) {
        return new IntentCandidate(
                IntentCategory.OTHER,
                0.0,
                reasoning,
                true
        );
    }
}
