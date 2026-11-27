package com.supportforge.api.dto;

import com.supportforge.evaluation.DialogTestCase;
import com.supportforge.evaluation.IntentTestCase;

import java.util.List;


public record EvalRunRequest(

        List<IntentTestCase> intentCases,

        List<DialogTestCase> dialogCases
) {

    public EvalRunRequest {

        if (intentCases != null) {
            intentCases = List.copyOf(intentCases);
        }


        if (dialogCases != null) {
            dialogCases = List.copyOf(dialogCases);
        }
    }
}
