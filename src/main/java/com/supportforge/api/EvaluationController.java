package com.supportforge.api;

import com.supportforge.api.dto.IntentEvaluationRequest;
import com.supportforge.api.dto.QualityEvaluationRequest;
import com.supportforge.evaluation.IntentEvaluationReport;
import com.supportforge.evaluation.IntentEvaluator;
import com.supportforge.evaluation.IntentTestCase;
import com.supportforge.evaluation.LlmJudge;
import com.supportforge.evaluation.QualityScores;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


import com.supportforge.api.dto.EvalRunRequest;
import com.supportforge.evaluation.DialogTestCase;
import com.supportforge.evaluation.EndToEndEvaluator;
import com.supportforge.evaluation.EvalReport;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;


@RestController
@RequestMapping("/eval")
@com.supportforge.security.RequiresAnyRole(com.supportforge.security.EnterpriseRole.ADMIN)
public class EvaluationController {


    private final IntentEvaluator intentEvaluator;


    private final LlmJudge llmJudge;


    private final EndToEndEvaluator endToEndEvaluator;


    public EvaluationController(
            IntentEvaluator intentEvaluator,
            LlmJudge llmJudge,
            EndToEndEvaluator endToEndEvaluator
    ) {
        this.intentEvaluator = intentEvaluator;
        this.llmJudge = llmJudge;
        this.endToEndEvaluator = endToEndEvaluator;
    }


    @PostMapping("/intent")
    public IntentEvaluationReport evaluateIntent(


            @RequestBody(required = false)
            IntentEvaluationRequest request
    ) {


        List<IntentTestCase> cases =
                request == null
                        || request.cases() == null
                        || request.cases().isEmpty()
                        ? intentEvaluator.defaultCases()
                        : request.cases();


        return intentEvaluator.evaluate(cases);
    }


    @PostMapping("/judge")
    public QualityScores judgeResponse(


            @RequestBody
            QualityEvaluationRequest request
    ) {


        return llmJudge.judge(
                request.question(),
                request.response(),
                request.context()
        );
    }


    @PostMapping("/run")
    public EvalReport runEvaluation(

            @RequestBody(required = false)
            EvalRunRequest request
    ) {


        List<IntentTestCase> intentCases =
                request == null
                        || request.intentCases() == null
                        ? intentEvaluator.defaultCases()
                        : request.intentCases();


        List<DialogTestCase> dialogCases =
                request == null
                        || request.dialogCases() == null
                        ? endToEndEvaluator
                        .defaultDialogCases()
                        : request.dialogCases();


        return endToEndEvaluator.run(
                intentCases,
                dialogCases
        );
    }


    @GetMapping("/history")
    @com.supportforge.security.RequiresAnyRole({com.supportforge.security.EnterpriseRole.ADMIN,
            com.supportforge.security.EnterpriseRole.AUDITOR})
    public List<EvalReport> evaluationHistory() {
        return endToEndEvaluator.history();
    }


    @GetMapping("/baseline")
    @com.supportforge.security.RequiresAnyRole({com.supportforge.security.EnterpriseRole.ADMIN,
            com.supportforge.security.EnterpriseRole.AUDITOR})
    public ResponseEntity<EvalReport> evaluationBaseline() {
        return endToEndEvaluator
                .latestReport()
                .map(ResponseEntity::ok)
                .orElseGet(


                        () -> ResponseEntity
                                .notFound()
                                .build()
                );
    }
}
