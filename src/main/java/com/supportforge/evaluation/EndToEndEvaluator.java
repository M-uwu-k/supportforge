package com.supportforge.evaluation;

import com.supportforge.persistence.port.EvaluationReportPersistencePort;
import com.supportforge.agent.AgentOrchestrator;
import com.supportforge.agent.AgentRequest;
import com.supportforge.agent.OrchestratorResult;
import com.supportforge.intent.IntentRecognizer;
import com.supportforge.intent.IntentResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;


@Component
public class EndToEndEvaluator {


    private static final double PASS_THRESHOLD =
            0.75;


    private static final List<DialogTestCase>
            DEFAULT_DIALOG_CASES = List.of(
            DialogTestCase.single(
                    "我的订单 #12345 还没到，已经超时了"
            ),
            DialogTestCase.single(
                    "应用登录一直报错401"
            ),
            DialogTestCase.single(
                    "为什么这个月多扣了50块钱？"
            ),
            DialogTestCase.single(
                    "帮我把收货地址改成北京市朝阳区"
            ),
            DialogTestCase.multi(
                    List.of(
                            "你好，我想退款",
                            "订单号是 #12345",
                            "退款多久能到账？"
                    )
            )
    );

    private final AgentOrchestrator orchestrator;
    private final IntentRecognizer intentRecognizer;
    private final IntentEvaluator intentEvaluator;
    private final LlmJudge llmJudge;


    private final EvaluationReportPersistencePort reportStore;
    private org.springframework.beans.factory.ObjectProvider<com.supportforge.grounding.EnterpriseChatService> enterpriseChat;


    @org.springframework.beans.factory.annotation.Autowired
    void configureEnterpriseChat(org.springframework.beans.factory.ObjectProvider<
            com.supportforge.grounding.EnterpriseChatService> enterpriseChat) {
        this.enterpriseChat = enterpriseChat;
    }


    private final ConcurrentMap<String, List<EvalReport>> histories =
            new ConcurrentHashMap<>();


    public EndToEndEvaluator(
            AgentOrchestrator orchestrator,
            IntentRecognizer intentRecognizer,
            IntentEvaluator intentEvaluator,
            LlmJudge llmJudge,
            EvaluationReportPersistencePort reportStore
    ) {
        this.orchestrator = orchestrator;
        this.intentRecognizer = intentRecognizer;
        this.intentEvaluator = intentEvaluator;
        this.llmJudge = llmJudge;
        this.reportStore = reportStore;


        this.histories.put(reportStore.scopeKey(), reportStore.load());
    }


    public List<DialogTestCase> defaultDialogCases() {
        return DEFAULT_DIALOG_CASES;
    }


    public List<EvalReport> history() {
        return currentHistory();
    }


    public Optional<EvalReport> latestReport() {


        List<EvalReport> snapshot = currentHistory();

        if (snapshot.isEmpty()) {
            return Optional.empty();
        }


        return Optional.of(
                snapshot.getLast()
        );
    }


    public synchronized EvalReport run(
            List<IntentTestCase> intentCases,
            List<DialogTestCase> dialogCases
    ) {

        boolean noIntentCases =
                intentCases == null
                        || intentCases.isEmpty();


        boolean noDialogCases =
                dialogCases == null
                        || dialogCases.isEmpty();


        if (noIntentCases && noDialogCases) {
            throw new IllegalArgumentException(
                    "至少需要提供一类评测用例"
            );
        }


        List<EvalResult> results =
                new ArrayList<>();


        Map<String, List<Double>> dialogScores =
                new LinkedHashMap<>();

        dialogScores.put(
                "relevance",
                new ArrayList<>()
        );
        dialogScores.put(
                "accuracy",
                new ArrayList<>()
        );
        dialogScores.put(
                "completeness",
                new ArrayList<>()
        );
        dialogScores.put(
                "helpfulness",
                new ArrayList<>()
        );


        IntentEvaluationReport intentReport = null;


        if (!noIntentCases) {
            intentReport =
                    intentEvaluator.evaluate(intentCases);


            boolean passed =
                    intentReport.accuracy()
                            >= PASS_THRESHOLD;


            Map<String, Object> metadata =
                    new LinkedHashMap<>();

            metadata.put(
                    "total",
                    intentReport.total()
            );
            metadata.put(
                    "correct",
                    intentReport.correct()
            );
            metadata.put(
                    "cases",
                    intentReport.cases()
            );
            metadata.put(
                    "per_class",
                    intentReport.perClass()
            );

            results.add(
                    new EvalResult(
                            "intent_recognition",
                            passed,
                            Map.of(
                                    "accuracy",
                                    intentReport.accuracy(),
                                    "macro_f1",
                                    intentReport.macroF1()
                            ),


                            String.format(
                                    Locale.ROOT,
                                    "准确率 %.1f%%，Macro-F1 %.4f",
                                    intentReport.accuracy() * 100,
                                    intentReport.macroF1()
                            ),
                            metadata
                    )
            );
        }


        if (!noDialogCases) {


            for (int caseIndex = 0;
                 caseIndex < dialogCases.size();
                 caseIndex++) {


                List<EvalResult> caseResults =
                        evaluateDialogCase(
                                dialogCases.get(caseIndex),
                                caseIndex
                        );


                results.addAll(caseResults);


                for (EvalResult result : caseResults) {


                    for (String metric :
                            dialogScores.keySet()) {


                        Double value =
                                result.scores()
                                        .get(metric);


                        if (value != null) {


                            dialogScores
                                    .get(metric)
                                    .add(value);
                        }
                    }
                }


            }
        }


        Map<String, Double> avgScores =
                new LinkedHashMap<>();


        for (Map.Entry<String, List<Double>> entry :
                dialogScores.entrySet()) {


            if (!entry.getValue().isEmpty()) {
                avgScores.put(
                        entry.getKey(),
                        round4(
                                average(entry.getValue())
                        )
                );
            }
        }


        if (intentReport != null) {
            avgScores.put(
                    "intent_accuracy",
                    intentReport.accuracy()
            );
        }


        int passedCount =
                (int) results.stream()
                        .filter(EvalResult::passed)
                        .count();


        double passRate =
                results.isEmpty()
                        ? 0.0
                        : (double) passedCount
                        / results.size();


        List<String> regressions =
                detectRegressions(avgScores);


        List<String> recommendations =
                recommendations(avgScores);

        EvalReport report =
                new EvalReport(
                        Instant.now().toString(),
                        results.size(),
                        passedCount,
                        round4(passRate),
                        avgScores,
                        regressions,
                        recommendations,
                        results
                );


        List<EvalReport> nextHistory =
                new ArrayList<>(currentHistory());

        nextHistory.add(report);


        List<EvalReport> limitedHistory =
                reportStore.keepLatest(nextHistory);


        reportStore.save(limitedHistory);


        histories.put(reportStore.scopeKey(), limitedHistory);

        return report;
    }


    private List<EvalResult> evaluateDialogCase(
            DialogTestCase testCase,
            int caseIndex
    ) {

        List<String> questions =
                testCase.questions();


        String conversationId =
                testCase.conversationId().isBlank()
                        ? "eval-" + caseIndex
                        : testCase.conversationId();


        List<String> historyLines =
                new ArrayList<>();

        List<EvalResult> results =
                new ArrayList<>();


        for (int turnIndex = 0;
             turnIndex < questions.size();
             turnIndex++) {

            String question =
                    questions.get(turnIndex);


            String context =
                    buildHistoryContext(historyLines);


            IntentResult intent =
                    intentRecognizer.recognize(question, context);

            AgentRequest agentRequest =
                    new AgentRequest(
                            question,
                            testCase.userId(),
                            conversationId,
                            context,
                            intent.entities(),
                            intent.intent(),
                            intent.intentGroup(),
                            intent.urgency(),
                            intent.confidence()
                    );


            var enterprise = enterpriseChat == null ? null : enterpriseChat.getIfAvailable();
            OrchestratorResult orchestratorResult = enterprise == null
                    ? orchestrator.run(agentRequest) : enterprise.run(agentRequest);

            String response =
                    orchestratorResult.response();

            QualityScores qualityScores;


            if (response == null
                    || response.isBlank()) {
                qualityScores =
                        QualityScores.failure(
                                "Agent没有生成有效回答"
                        );
            } else {

                qualityScores =
                        llmJudge.judge(
                                question,
                                response,
                                context
                        );
            }


            boolean passed =
                    !qualityScores.judgeFailed()
                            && qualityScores.overall()
                            >= PASS_THRESHOLD;


            historyLines.add(
                    "user: " + question
            );
            historyLines.add(
                    "assistant: "
                            + (
                            response == null
                                    ? ""
                                    : response
                    )
            );


            String testId =
                    questions.size() == 1
                            ? "dialog_" + caseIndex
                            : "dialog_"
                            + caseIndex
                            + "_turn_"
                            + turnIndex;


            Map<String, Double> scores =
                    new LinkedHashMap<>();

            scores.put(
                    "relevance",
                    qualityScores.relevance()
            );
            scores.put(
                    "accuracy",
                    qualityScores.accuracy()
            );
            scores.put(
                    "completeness",
                    qualityScores.completeness()
            );
            scores.put(
                    "helpfulness",
                    qualityScores.helpfulness()
            );
            scores.put(
                    "overall",
                    qualityScores.overall()
            );


            Map<String, Object> metadata =
                    new LinkedHashMap<>();

            metadata.put("question", question);
            metadata.put("response", response);
            metadata.put(
                    "agent_type",
                    orchestratorResult
                            .agentType()
                            .value()
            );
            metadata.put(
                    "intent",
                    intent.intent().value()
            );
            metadata.put("turn", turnIndex);
            metadata.put(
                    "conversation_id",
                    conversationId
            );
            metadata.put(
                    "judge_failed",
                    qualityScores.judgeFailed()
            );


            if (qualityScores.error() != null) {
                metadata.put(
                        "judge_error",
                        qualityScores.error()
                );
            }


            results.add(
                    new EvalResult(
                            testId,
                            passed,
                            scores,
                            String.format(
                                    Locale.ROOT,
                                    "Q: %s → 综合评分 %.4f",
                                    abbreviate(question, 30),
                                    qualityScores.overall()
                            ),
                            metadata
                    )
            );
        }

        return results;
    }


    private String buildHistoryContext(
            List<String> historyLines
    ) {

        if (historyLines.isEmpty()) {
            return "";
        }


        int fromIndex =
                Math.max(
                        0,
                        historyLines.size() - 8
                );


        return "[评测多轮历史]\n"
                + String.join(
                "\n",
                historyLines.subList(
                        fromIndex,
                        historyLines.size()
                )
        );
    }


    private List<String> detectRegressions(


            Map<String, Double> currentScores
    ) {


        List<EvalReport> history = currentHistory();
        if (history.isEmpty()) {
            return List.of();
        }


        EvalReport previous =
                history.getLast();


        List<String> regressions =
                new ArrayList<>();


        for (Map.Entry<String, Double> current :
                currentScores.entrySet()) {


            Double previousValue =
                    previous.avgScores()
                            .get(current.getKey());


            if (previousValue == null
                    || previousValue <= 0) {

                continue;
            }


            double change =
                    (
                            current.getValue()
                                    - previousValue
                    ) / previousValue;


            if (change < -0.05) {


                regressions.add(
                        String.format(
                                Locale.ROOT,
                                "%s: %.4f → %.4f（退化 %.1f%%）",
                                current.getKey(),
                                previousValue,
                                current.getValue(),
                                Math.abs(change) * 100
                        )
                );
            }
        }


        return List.copyOf(regressions);
    }


    private List<String> recommendations(
            Map<String, Double> scores
    ) {
        List<String> recommendations =
                new ArrayList<>();


        if (scores.getOrDefault(
                "intent_accuracy",
                1.0
        ) < 0.90) {
            recommendations.add(
                    "意图识别准确率低于90%：补充低F1类别的关键词和测试样本"
            );
        }

        if (scores.getOrDefault(
                "relevance",
                1.0
        ) < 0.75) {
            recommendations.add(
                    "回答相关性偏低：检查Agent提示词和路由规则"
            );
        }

        if (scores.getOrDefault(
                "completeness",
                1.0
        ) < 0.75) {
            recommendations.add(
                    "回答完整性偏低：要求Agent提供完整处理步骤"
            );
        }

        if (scores.getOrDefault(
                "helpfulness",
                1.0
        ) < 0.75) {
            recommendations.add(
                    "回答有用性偏低：增加明确、可执行的操作建议"
            );
        }


        if (recommendations.isEmpty()) {
            recommendations.add(
                    "所有指标均达到当前目标"
            );
        }

        return List.copyOf(recommendations);
    }


    private double average(
            List<Double> values
    ) {
        return values.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
    }


    private String abbreviate(
            String value,
            int maximumLength
    ) {
        if (value.length() <= maximumLength) {
            return value;
        }


        return value.substring(
                0,
                maximumLength
        ) + "...";
    }

    private List<EvalReport> currentHistory() {
        return histories.computeIfAbsent(
                reportStore.scopeKey(), ignored -> reportStore.load()
        );
    }


    private double round4(double value) {
        return Math.round(value * 10_000.0)
                / 10_000.0;
    }
}
