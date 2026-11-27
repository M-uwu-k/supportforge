package com.supportforge.intent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateEmbeddingIntentRecognizerTest {

    private TemplateEmbeddingIntentRecognizer recognizer;

    @BeforeEach
    void setUp() {

        recognizer = TemplateEmbeddingIntentRecognizer.defaults();
    }

    @Test
    void shouldReturnExactTemplateIntentWithMaximumSimilarity() {
        IntentCandidate candidate = recognizer.recognize(
                "我要申请退款"
        );

        assertEquals(IntentCategory.REFUND, candidate.intent());
        assertEquals(1.0, candidate.confidence(), 0.000001);
        assertTrue(candidate.reasoning().contains("我要申请退款"));
    }

    @Test
    void shouldRecognizeAnotherExactBusinessTemplate() {
        IntentCandidate candidate = recognizer.recognize(
                "支付失败怎么办？"
        );

        assertEquals(IntentCategory.PAYMENT_ISSUE, candidate.intent());
        assertEquals(1.0, candidate.confidence(), 0.000001);
        assertFalse(candidate.failed());
    }

    @Test
    void shouldNormalizeWhitespaceAroundMessage() {
        IntentCandidate candidate = recognizer.recognize(
                "   电子发票在哪里？   "
        );

        assertEquals(IntentCategory.INVOICE, candidate.intent());
        assertEquals(1.0, candidate.confidence(), 0.000001);
    }

    @Test
    void shouldKeepEarlierIntentWhenDuplicateTemplatesTie() {
        IntentCandidate query = recognizer.recognize(
                "快递什么时候到？"
        );
        IntentCandidate escalation = recognizer.recognize(
                "转人工客服"
        );

        assertEquals(IntentCategory.QUERY, query.intent());
        assertEquals(1.0, query.confidence(), 0.000001);
        assertEquals(IntentCategory.ESCALATION, escalation.intent());
        assertEquals(1.0, escalation.confidence(), 0.000001);
    }

    @Test
    void shouldReturnSuccessfulOtherForBlankMessage() {
        IntentCandidate candidate = recognizer.recognize("   ");

        assertEquals(IntentCategory.OTHER, candidate.intent());
        assertEquals(0.0, candidate.confidence());
        assertFalse(candidate.failed());
    }

    @Test
    void shouldRejectTooSmallDimensionsAndKeepScoresBounded() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TemplateEmbeddingIntentRecognizer(31)
        );

        IntentCandidate candidate = recognizer.recognize(
                "我想退货并申请退款"
        );

        assertTrue(candidate.confidence() >= 0.0);
        assertTrue(candidate.confidence() <= 1.0);
    }


    @Test
    void shouldLearnCorrectionAndOverrideBuiltInTemplateTie() {
        IntentCandidate before = recognizer.recognize("申请退款");

        boolean added = recognizer.learn(
                "申请退款",
                IntentCategory.REFUND
        );
        IntentCandidate after = recognizer.recognize("申请退款");


        assertEquals(IntentCategory.BILLING, before.intent());
        assertTrue(added);
        assertEquals(IntentCategory.REFUND, after.intent());
        assertEquals(1.0, after.confidence(), 0.000001);
        assertTrue(after.reasoning().contains("学习样本"));
    }


    @Test
    void shouldIgnoreDuplicateCorrectionForSameIntent() {
        assertTrue(recognizer.learn(
                "包裹卡在中转站",
                IntentCategory.LOGISTICS
        ));
        assertFalse(recognizer.learn(
                "  包裹卡在中转站  ",
                IntentCategory.LOGISTICS
        ));
        assertEquals(1, recognizer.learnedTemplateCount());
    }


    @Test
    void shouldReplacePreviousCorrectionForSameMessage() {
        assertTrue(recognizer.learn(
                "我要处理这个问题",
                IntentCategory.REQUEST
        ));
        assertTrue(recognizer.learn(
                "我要处理这个问题",
                IntentCategory.HUMAN_HANDOFF
        ));

        IntentCandidate candidate =
                recognizer.recognize("我要处理这个问题");

        assertEquals(IntentCategory.HUMAN_HANDOFF, candidate.intent());
        assertEquals(1, recognizer.learnedTemplateCount());
    }


    @Test
    void shouldRejectInvalidLearningExamples() {
        assertThrows(
                IllegalArgumentException.class,
                () -> recognizer.learn("   ", IntentCategory.REFUND)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> recognizer.learn("测试", null)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> recognizer.learn("测试", IntentCategory.OTHER)
        );
    }
}
