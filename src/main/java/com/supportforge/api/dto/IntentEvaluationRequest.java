package com.supportforge.api.dto;

import com.supportforge.evaluation.IntentTestCase;

import java.util.List;


public record IntentEvaluationRequest(

        List<IntentTestCase> cases
) {
    public IntentEvaluationRequest {


        if (cases != null) {
            cases = List.copyOf(cases);
        }
    }
}
