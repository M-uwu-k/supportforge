package com.supportforge.intent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IntentRecognizerTest {

    private IntentRecognizer recognizer;

    @BeforeEach
    void setUp() {
        EntityExtractor entityExtractor =
                new EntityExtractor();

        recognizer =
                new IntentRecognizer(entityExtractor);
    }

    @Test
    void shouldRecognizeTechnicalLoginAndEntities() {
        IntentResult result = recognizer.recognize(
                "紧急，订单A12345登录时报错401，支付了99元"
        );

        assertAll(
                () -> assertEquals(
                        IntentCategory.TECHNICAL_LOGIN,
                        result.intent()
                ),

                () -> assertEquals(
                        "technical",
                        result.intentGroup()
                ),

                () -> assertEquals(
                        UrgencyLevel.CRITICAL,
                        result.urgency()
                ),

                () -> assertEquals(
                        0.85,
                        result.confidence()
                ),

                () -> assertEquals(
                        List.of("A12345"),
                        result.entities().get("order_id")
                ),

                () -> assertEquals(
                        List.of("99元"),
                        result.entities().get("amount")
                ),

                () -> assertEquals(
                        List.of("401"),
                        result.entities().get("error_code")
                )
        );
    }

    @Test
    void shouldRecognizePaymentIssue() {
        IntentResult result = recognizer.recognize(
                "订单B9988重复扣款199元"
        );

        assertAll(
                () -> assertEquals(
                        IntentCategory.PAYMENT_ISSUE,
                        result.intent()
                ),

                () -> assertEquals(
                        "billing",
                        result.intentGroup()
                ),

                () -> assertEquals(
                        List.of("B9988"),
                        result.entities().get("order_id")
                ),

                () -> assertEquals(
                        List.of("199元"),
                        result.entities().get("amount")
                )
        );
    }

    @Test
    void shouldPreferSpecificIntentByRuleOrder() {
        IntentResult result = recognizer.recognize(
                "我登录失败，同时还想申请退款"
        );


        assertEquals(
                IntentCategory.TECHNICAL_LOGIN,
                result.intent()
        );
    }

    @Test
    void shouldDetectHumanHandoff() {
        IntentResult result = recognizer.recognize(
                "这个问题一直没处理，请帮我转人工客服"
        );

        assertAll(
                () -> assertEquals(
                        IntentCategory.HUMAN_HANDOFF,
                        result.intent()
                ),

                () -> assertEquals(
                        "escalation",
                        result.intentGroup()
                ),

                () -> assertEquals(
                        UrgencyLevel.HIGH,
                        result.urgency()
                ),

                () -> assertEquals(
                        0.95,
                        result.confidence()
                )
        );
    }

    @Test
    void shouldReturnOtherForUnknownMessage() {
        IntentResult result = recognizer.recognize(
                "随便说一段没有关键词的话"
        );

        assertAll(
                () -> assertEquals(
                        IntentCategory.OTHER,
                        result.intent()
                ),

                () -> assertEquals(
                        UrgencyLevel.LOW,
                        result.urgency()
                ),

                () -> assertEquals(
                        0.20,
                        result.confidence()
                )
        );
    }

    @Test
    void shouldHandleNullMessage() {
        IntentResult result =
                recognizer.recognize(null);

        assertAll(
                () -> assertEquals(
                        IntentCategory.OTHER,
                        result.intent()
                ),

                () -> assertTrue(
                        result.entities()
                                .get("order_id")
                                .isEmpty()
                ),

                () -> assertTrue(
                        result.entities()
                                .get("amount")
                                .isEmpty()
                ),

                () -> assertTrue(
                        result.entities()
                                .get("error_code")
                                .isEmpty()
                )
        );
    }
}