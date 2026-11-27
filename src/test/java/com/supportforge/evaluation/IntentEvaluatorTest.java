package com.supportforge.evaluation;

import com.supportforge.intent.EntityExtractor;
import com.supportforge.intent.IntentRecognizer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentEvaluatorTest {

    private final IntentEvaluator evaluator =
            new IntentEvaluator(
                    new IntentRecognizer(
                            new EntityExtractor()
                    )
            );

    @Test
    void shouldCalculateAccuracyAndMacroF1() {
        IntentEvaluationReport report = evaluator.evaluate(
                List.of(
                        new IntentTestCase("你好", "greeting"),
                        new IntentTestCase("我要退款", "refund"),
                        new IntentTestCase(
                                "登录一直报401",
                                "technical_login"
                        ),
                        new IntentTestCase("你好", "refund")
                )
        );

        assertEquals(4, report.total());
        assertEquals(3, report.correct());
        assertEquals(0.75, report.accuracy());
        assertEquals(0.7778, report.macroF1());

        IntentClassMetrics greeting =
                report.perClass().get("greeting");
        assertEquals(1, greeting.truePositive());
        assertEquals(1, greeting.falsePositive());
        assertEquals(0, greeting.falseNegative());
        assertEquals(0.5, greeting.precision());
        assertEquals(1.0, greeting.recall());
        assertEquals(0.6667, greeting.f1());

        assertTrue(report.cases().getFirst().correct());
        assertFalse(report.cases().getLast().correct());
        assertEquals("greeting",
                report.cases().getLast().predicted());
    }

    @Test
    void shouldRejectEmptyCasesAndUnknownExpectedIntent() {
        IllegalArgumentException empty = assertThrows(
                IllegalArgumentException.class,
                () -> evaluator.evaluate(List.of())
        );
        assertEquals("意图评测用例不能为空", empty.getMessage());

        IllegalArgumentException unknown = assertThrows(
                IllegalArgumentException.class,
                () -> evaluator.evaluate(
                        List.of(
                                new IntentTestCase(
                                        "我要退款",
                                        "refnud"
                                )
                        )
                )
        );
        assertEquals(
                "不支持的预期意图：refnud",
                unknown.getMessage()
        );
    }

    @Test
    void shouldProvideDefaultEvaluationCases() {
        assertEquals(11, evaluator.defaultCases().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> evaluator.defaultCases().add(
                        new IntentTestCase("测试", "other")
                )
        );
    }
}
