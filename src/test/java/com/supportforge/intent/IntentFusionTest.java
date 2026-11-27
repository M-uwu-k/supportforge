package com.supportforge.intent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentFusionTest {

    private final IntentFusion fusion = IntentFusion.defaults();

    @Test
    void shouldAccumulateWeightedScoresForSameIntent() {
        IntentFusionResult result = fusion.fuse(
                candidate(IntentCategory.REFUND, 0.90),
                candidate(IntentCategory.BILLING, 0.80),
                candidate(IntentCategory.REFUND, 0.75)
        );

        assertEquals(IntentCategory.REFUND, result.intent());
        assertEquals(0.705, result.confidence(), 0.000001);
        assertEquals(0.90, result.sourceScores().get("llm"));
        assertEquals(0.80, result.sourceScores().get("embedding"));
        assertEquals(0.75, result.sourceScores().get("pattern"));
    }

    @Test
    void shouldReturnOtherWhenWinningScoreIsBelowThreshold() {
        IntentFusionResult result = fusion.fuse(
                candidate(IntentCategory.QUERY, 0.30),
                candidate(IntentCategory.QUERY, 0.30),
                candidate(IntentCategory.QUERY, 0.30)
        );

        assertEquals(IntentCategory.OTHER, result.intent());
        assertEquals(0.30, result.confidence(), 0.000001);
        assertTrue(result.reasoning().contains("低于阈值"));
    }

    @Test
    void shouldUseEmbeddingWhenLlmFails() {
        IntentFusionResult result = fusion.fuse(
                IntentCandidate.failure("模型超时"),
                candidate(IntentCategory.LOGISTICS, 0.72),
                candidate(IntentCategory.ORDER_STATUS, 0.95)
        );

        assertEquals(IntentCategory.LOGISTICS, result.intent());
        assertEquals(0.72, result.confidence(), 0.000001);
        assertTrue(result.reasoning().contains("向量结果"));
    }

    @Test
    void shouldPreferPatternToLowConfidenceEmbeddingWhenLlmFails() {
        IntentFusionResult result = fusion.fuse(
                IntentCandidate.failure("模型返回了非JSON文本"),
                candidate(IntentCategory.REFUND, 0.45),
                candidate(IntentCategory.QUERY, 0.75)
        );

        assertEquals(IntentCategory.QUERY, result.intent());
        assertEquals(0.75, result.confidence(), 0.000001);
        assertTrue(result.reasoning().contains("关键词结果"));
    }

    @Test
    void shouldUsePatternWhenLlmAndEmbeddingFail() {
        IntentFusionResult result = fusion.fuse(
                IntentCandidate.failure("模型超时"),
                IntentCandidate.failure("向量异常"),
                candidate(IntentCategory.HUMAN_HANDOFF, 0.75)
        );

        assertEquals(IntentCategory.HUMAN_HANDOFF, result.intent());
        assertEquals(0.75, result.confidence(), 0.000001);
        assertTrue(result.reasoning().contains("使用关键词结果"));
    }

    @Test
    void shouldRefineGenericWinnerWithPatternFromSameGroup() {
        IntentFusionResult result = fusion.fuse(
                candidate(IntentCategory.BILLING, 0.80),
                candidate(IntentCategory.BILLING, 0.80),
                candidate(IntentCategory.REFUND, 0.85)
        );

        assertEquals(IntentCategory.REFUND, result.intent());
        assertEquals(0.85, result.confidence(), 0.000001);
        assertEquals(
                0.85,
                result.sourceScores().get("refined_by_pattern")
        );
    }

    @Test
    void shouldNotRefineGenericWinnerWithPatternFromDifferentGroup() {
        IntentFusionResult result = fusion.fuse(
                candidate(IntentCategory.BILLING, 0.80),
                candidate(IntentCategory.BILLING, 0.80),
                candidate(IntentCategory.TECHNICAL_LOGIN, 0.85)
        );

        assertEquals(IntentCategory.BILLING, result.intent());
        assertEquals(0.72, result.confidence(), 0.000001);
        assertFalse(
                result.sourceScores()
                        .containsKey("refined_by_pattern")
        );
    }

    @Test
    void shouldValidateWeightsAndNormalizeCandidateValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new IntentFusion(
                        0.70,
                        0.20,
                        0.20,
                        0.50
                )
        );

        IntentCandidate candidate = new IntentCandidate(
                null,
                2.0,
                "  原因  ",
                false
        );

        assertEquals(IntentCategory.OTHER, candidate.intent());
        assertEquals(1.0, candidate.confidence());
        assertEquals("原因", candidate.reasoning());
    }

    private IntentCandidate candidate(
            IntentCategory intent,
            double confidence
    ) {
        return IntentCandidate.success(
                intent,
                confidence,
                "测试候选"
        );
    }
}
