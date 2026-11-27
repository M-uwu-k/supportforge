package com.supportforge.grounding;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundedAnswerGuardTest {

    @Test
    void acceptsCitedAnswerWhoseNumbersExistInEvidence() {
        UUID point = UUID.randomUUID();
        GroundingDecision decision = allowed(point, "退款在3个工作日内到账");

        AnswerGroundingResult result = new GroundedAnswerGuard().verify(
                "退款会在3个工作日内到账。", decision, Set.of(point)
        );

        assertTrue(result.grounded());
    }

    @Test
    void rejectsUnknownCitationAndUnsupportedNumber() {
        GroundingDecision decision = allowed(
                UUID.randomUUID(), "退款在3个工作日内到账"
        );

        AnswerGroundingResult result = new GroundedAnswerGuard().verify(
                "退款会在7个工作日内到账。",
                decision, Set.of(UUID.randomUUID())
        );

        assertFalse(result.grounded());
        assertTrue(result.issues().size() >= 2);
    }

    @Test
    void citationAndListNumbersAreNotBusinessClaimsButSubstringsAreNotEvidence() {
        UUID point = UUID.randomUUID();
        GroundingDecision decision = allowed(point, "退款在30个工作日内到账，金额99元。");
        GroundedAnswerGuard guard = new GroundedAnswerGuard();
        assertTrue(guard.verify("1. 退款30个工作日到账，金额99.00元。[E1]", decision, Set.of(point)).grounded());
        assertFalse(guard.verify("退款3个工作日到账。[E1]", decision, Set.of(point)).grounded());
    }

    @Test
    void rejectsMissingOrBlankAnswerEvenWithValidEvidenceAndCitation() {
        UUID point = UUID.randomUUID();
        GroundingDecision decision = allowed(point, "退款在3个工作日内到账");
        GroundedAnswerGuard guard = new GroundedAnswerGuard();
        for (String answer : new String[]{null, "", " \t\r\n "}) {
            AnswerGroundingResult result = guard.verify(answer, decision, Set.of(point));
            assertFalse(result.grounded());
            assertTrue(result.issues().contains("回答正文不能为空"));
        }
    }

    @Test
    void citationLabelsAloneDoNotCountAsAnswerText() {
        UUID point = UUID.randomUUID();
        var result = new GroundedAnswerGuard().verify("[E1]", allowed(point, "有效证据"), Set.of(point));
        assertFalse(result.grounded());
        assertFalse(result.issues().isEmpty());
    }

    private GroundingDecision allowed(UUID point, String content) {
        GroundingEvidence evidence = new GroundingEvidence(
                point, UUID.randomUUID(), UUID.randomUUID(), "external", 0,
                content,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                        + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "BILLING", 0.03, 0.9
        );
        return new GroundingDecision(
                true, GroundingRefusalReason.NONE, "", 0.8,
                List.of(evidence), "context"
        );
    }
}
