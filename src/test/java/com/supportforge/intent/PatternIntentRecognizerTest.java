package com.supportforge.intent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternIntentRecognizerTest {

    private PatternIntentRecognizer recognizer;

    @BeforeEach
    void setUp() {
        recognizer = new PatternIntentRecognizer();
    }

    @Test
    void shouldPreferSpecificIntentOverGenericIntent() {
        IntentCandidate candidate = recognizer.recognize(
                "请帮我申请退款"
        );

        assertEquals(IntentCategory.REFUND, candidate.intent());
        assertEquals(0.50, candidate.confidence());
        assertTrue(candidate.reasoning().contains("退款"));
    }

    @Test
    void shouldChooseIntentWithMoreMatchedKeywords() {
        IntentCandidate candidate = recognizer.recognize(
                "我要退款退货，订单还发生了重复扣款"
        );

        assertEquals(IntentCategory.REFUND, candidate.intent());
        assertEquals(0.75, candidate.confidence());
        assertTrue(candidate.reasoning().contains("退款、退货"));
    }

    @Test
    void shouldRecognizeLoginErrorBeforeEqualPaymentIssue() {
        IntentCandidate candidate = recognizer.recognize(
                "登录时报错401，同时重复扣款99元"
        );

        assertEquals(IntentCategory.TECHNICAL_LOGIN, candidate.intent());
        assertEquals(0.75, candidate.confidence());
        assertTrue(candidate.reasoning().contains("登录时报错"));
        assertTrue(candidate.reasoning().contains("401"));
    }

    @Test
    void shouldKeepEarlierRuleWhenScoresAreEqual() {
        IntentCandidate candidate = recognizer.recognize(
                "订单需要退款，而且发生了重复扣款"
        );

        assertEquals(IntentCategory.REFUND, candidate.intent());
        assertEquals(0.50, candidate.confidence());
    }

    @Test
    void shouldScoreMultipleGenericKeywords() {
        IntentCandidate candidate = recognizer.recognize(
                "请查询账单费用"
        );

        assertEquals(IntentCategory.BILLING, candidate.intent());
        assertEquals(0.75, candidate.confidence());
    }

    @Test
    void shouldNormalizeEnglishCaseAndCapScoreAtOne() {
        IntentCandidate payment = recognizer.recognize(
                "PAYMENT FAILED"
        );
        IntentCandidate invoice = recognizer.recognize(
                "电子发票的发票抬头和税号怎么填写"
        );

        assertEquals(IntentCategory.PAYMENT_ISSUE, payment.intent());
        assertEquals(0.50, payment.confidence());
        assertEquals(IntentCategory.INVOICE, invoice.intent());
        assertEquals(1.0, invoice.confidence());
    }

    @Test
    void shouldReturnSuccessfulOtherForNullAndUnknownMessages() {
        IntentCandidate nullCandidate = recognizer.recognize(null);
        IntentCandidate unknown = recognizer.recognize(
                "这是一段没有业务关键词的文字"
        );

        assertEquals(IntentCategory.OTHER, nullCandidate.intent());
        assertEquals(0.0, nullCandidate.confidence());
        assertFalse(nullCandidate.failed());
        assertEquals(IntentCategory.OTHER, unknown.intent());
        assertFalse(unknown.failed());
    }
}
