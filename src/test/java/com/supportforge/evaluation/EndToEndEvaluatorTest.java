package com.supportforge.evaluation;

import com.supportforge.agent.AgentOrchestrator;
import com.supportforge.agent.AgentType;
import com.supportforge.agent.OrchestratorResult;
import com.supportforge.agent.AgentRequest;
import com.supportforge.intent.EntityExtractor;
import com.supportforge.intent.IntentRecognizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;

class EndToEndEvaluatorTest {

    @TempDir
    Path tempDir;

    private int storeIndex;

    @Test
    void classifierGeneratorAndJudgeShareHistoryWithoutLeakingAcrossCases() {
        AgentOrchestrator orchestrator = cannedOrchestrator();
        LlmJudge judge = mock(LlmJudge.class);
        when(judge.judge(anyString(), anyString(), anyString())).thenReturn(scores(0.9));
        IntentRecognizer recognizer = spy(new IntentRecognizer(new EntityExtractor()));
        EndToEndEvaluator evaluator = new EndToEndEvaluator(orchestrator, recognizer,
                new IntentEvaluator(recognizer), judge, new EvaluationReportStore(new ObjectMapper(),
                tempDir.resolve("shared-context.json").toString(), 50));

        evaluator.run(List.of(), List.of(DialogTestCase.multi(List.of("订单重复扣款", "这个问题怎么办？")),
                DialogTestCase.single("独立问题")));

        ArgumentCaptor<String> recognitionHistory = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AgentRequest> agentRequests = ArgumentCaptor.forClass(AgentRequest.class);
        ArgumentCaptor<String> judgeHistory = ArgumentCaptor.forClass(String.class);
        verify(recognizer, times(3)).recognize(anyString(), recognitionHistory.capture());
        verify(orchestrator, times(3)).run(agentRequests.capture());
        verify(judge, times(3)).judge(anyString(), anyString(), judgeHistory.capture());
        List<String> histories = recognitionHistory.getAllValues();
        assertEquals("", histories.get(0));
        assertTrue(histories.get(1).contains("user: 订单重复扣款"));
        assertTrue(histories.get(1).contains("assistant: 测试回答"));
        assertEquals("", histories.get(2));
        assertEquals(histories, agentRequests.getAllValues().stream().map(AgentRequest::context).toList());
        assertEquals(histories, judgeHistory.getAllValues());
    }

    @Test
    void shouldCombineIntentAndDialogEvaluation() {
        AgentOrchestrator orchestrator = cannedOrchestrator();
        LlmJudge judge = mock(LlmJudge.class);
        when(judge.judge(anyString(), anyString(), anyString()))
                .thenReturn(scores(0.8));

        EndToEndEvaluator evaluator = evaluator(orchestrator, judge);

        EvalReport report = evaluator.run(
                List.of(new IntentTestCase("你好", "greeting")),
                List.of(DialogTestCase.single("你好"))
        );

        assertEquals(2, report.total());
        assertEquals(2, report.passed());
        assertEquals(1.0, report.passRate());
        assertEquals(1.0, report.avgScores().get("intent_accuracy"));
        assertEquals(0.8, report.avgScores().get("relevance"));
        assertEquals("intent_recognition", report.results().get(0).testId());
        assertEquals("dialog_0", report.results().get(1).testId());
        assertEquals(1, evaluator.history().size());
    }

    @Test
    void shouldCarryPreviousAnswersIntoMultiTurnContext() {
        AgentOrchestrator orchestrator = cannedOrchestrator();
        LlmJudge judge = mock(LlmJudge.class);
        when(judge.judge(anyString(), anyString(), anyString()))
                .thenReturn(scores(0.9));

        EndToEndEvaluator evaluator = evaluator(orchestrator, judge);

        EvalReport report = evaluator.run(
                List.of(),
                List.of(DialogTestCase.multi(List.of("第一问", "第二问")))
        );

        assertEquals(2, report.total());
        assertEquals("dialog_0_turn_0", report.results().get(0).testId());
        assertEquals("dialog_0_turn_1", report.results().get(1).testId());

        ArgumentCaptor<String> contextCaptor =
                ArgumentCaptor.forClass(String.class);
        verify(judge, times(2)).judge(
                anyString(),
                anyString(),
                contextCaptor.capture()
        );

        List<String> contexts = contextCaptor.getAllValues();
        assertEquals("", contexts.get(0));
        assertTrue(contexts.get(1).contains("user: 第一问"));
        assertTrue(contexts.get(1).contains("assistant: 测试回答"));
    }

    @Test
    void shouldDetectRegressionAgainstPreviousRun() {
        AgentOrchestrator orchestrator = cannedOrchestrator();
        LlmJudge judge = mock(LlmJudge.class);
        AtomicReference<Double> score = new AtomicReference<>(0.9);
        when(judge.judge(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> scores(score.get()));

        EndToEndEvaluator evaluator = evaluator(orchestrator, judge);
        List<DialogTestCase> cases =
                List.of(DialogTestCase.single("你好"));

        EvalReport first = evaluator.run(List.of(), cases);
        score.set(0.7);
        EvalReport second = evaluator.run(List.of(), cases);

        assertTrue(first.regressions().isEmpty());
        assertFalse(second.regressions().isEmpty());
        assertEquals(0, second.passed());
        assertEquals(2, evaluator.history().size());
        assertTrue(second.regressions().stream()
                .anyMatch(value -> value.contains("relevance")));
    }

    @Test
    void shouldFailDialogWhenJudgeFails() {
        AgentOrchestrator orchestrator = cannedOrchestrator();
        LlmJudge judge = mock(LlmJudge.class);
        when(judge.judge(anyString(), anyString(), anyString()))
                .thenReturn(QualityScores.failure("无有效JSON"));

        EvalReport report = evaluator(orchestrator, judge).run(
                List.of(),
                List.of(DialogTestCase.single("你好"))
        );

        EvalResult result = report.results().get(0);
        assertFalse(result.passed());
        assertEquals(true, result.metadata().get("judge_failed"));
        assertEquals("无有效JSON", result.metadata().get("judge_error"));
    }

    @Test
    void shouldRejectRunWithoutAnyCases() {
        EndToEndEvaluator evaluator = evaluator(
                cannedOrchestrator(),
                mock(LlmJudge.class)
        );

        Executable action = () -> evaluator.run(List.of(), List.of());

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, action);
        assertEquals("至少需要提供一类评测用例", exception.getMessage());
    }

    @Test
    void shouldRestoreHistoryAndDetectRegressionAfterRestart() {
        Path reportPath = tempDir.resolve("restart/reports.json");
        EvaluationReportStore store = new EvaluationReportStore(
                new ObjectMapper(),
                reportPath.toString(),
                50
        );

        LlmJudge firstJudge = mock(LlmJudge.class);
        when(firstJudge.judge(anyString(), anyString(), anyString()))
                .thenReturn(scores(0.9));
        EndToEndEvaluator first = evaluator(
                cannedOrchestrator(),
                firstJudge,
                store
        );
        first.run(
                List.of(),
                List.of(DialogTestCase.single("你好"))
        );

        LlmJudge secondJudge = mock(LlmJudge.class);
        when(secondJudge.judge(anyString(), anyString(), anyString()))
                .thenReturn(scores(0.7));
        EndToEndEvaluator restarted = evaluator(
                cannedOrchestrator(),
                secondJudge,
                store
        );
        EvalReport second = restarted.run(
                List.of(),
                List.of(DialogTestCase.single("你好"))
        );

        assertEquals(2, restarted.history().size());
        assertEquals(second, restarted.latestReport().orElseThrow());
        assertFalse(second.regressions().isEmpty());
    }

    private EndToEndEvaluator evaluator(
            AgentOrchestrator orchestrator,
            LlmJudge judge
    ) {
        EvaluationReportStore store = new EvaluationReportStore(
                new ObjectMapper(),
                tempDir.resolve(
                        "evaluator-" + storeIndex++ + "/reports.json"
                ).toString(),
                50
        );
        return evaluator(orchestrator, judge, store);
    }

    private EndToEndEvaluator evaluator(
            AgentOrchestrator orchestrator,
            LlmJudge judge,
            EvaluationReportStore store
    ) {
        IntentRecognizer recognizer =
                new IntentRecognizer(new EntityExtractor());
        return new EndToEndEvaluator(
                orchestrator,
                recognizer,
                new IntentEvaluator(recognizer),
                judge,
                store
        );
    }

    private AgentOrchestrator cannedOrchestrator() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        when(orchestrator.run(any())).thenReturn(
                new OrchestratorResult(
                        "测试回答",
                        AgentType.GENERAL,
                        List.of(AgentType.GENERAL),
                        AgentType.GENERAL,
                        List.of(),
                        "测试路由",
                        1.0,
                        false,
                        1.0,
                        List.of()
                )
        );
        return orchestrator;
    }

    private QualityScores scores(double value) {
        return new QualityScores(
                value,
                value,
                value,
                value,
                false,
                null
        );
    }
}
