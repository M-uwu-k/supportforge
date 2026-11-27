package com.supportforge.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class KnowledgeHybridScorerTest {


    @Test
    void shouldPreserveKeywordMatchesAndLexicalScore() {
        KnowledgeHybridScorer scorer = scorer();

        KnowledgeSearchResult result = scorer.score(
                document(
                        "refund",
                        "退款申请规则",
                        "申请退款需要订单记录",
                        List.of("退款", "订单")
                ),
                "我要查询退款订单",
                KnowledgeCategory.BILLING
        );

        assertEquals(
                List.of("退款", "订单"),
                result.matchedKeywords()
        );
        assertTrue(result.score() > 2.0);
    }


    @Test
    void shouldScoreRelevantTitleAndContentWithoutKeywordHit() {
        KnowledgeHybridScorer scorer = scorer();

        KnowledgeSearchResult result = scorer.score(
                document(
                        "two-factor",
                        "账户双重验证",
                        "双重验证可以提升账户安全",
                        List.of("二次校验")
                ),
                "如何开启双重验证提升账户安全",
                KnowledgeCategory.TECHNICAL
        );

        assertTrue(result.matchedKeywords().isEmpty());
        assertTrue(result.score() > 0.0);
    }


    @Test
    void shouldAddCategoryBoostOnlyForMatchingCategory() {
        KnowledgeHybridScorer scorer = scorer();

        KnowledgeDocument document = document(
                "invoice",
                "发票规则",
                "申请电子发票",
                List.of("发票")
        );

        double withoutCategory = scorer.score(
                document,
                "申请发票",
                null
        ).score();

        double withCategory = scorer.score(
                document,
                "申请发票",
                KnowledgeCategory.BILLING
        ).score();

        assertEquals(
                0.5,
                withCategory - withoutCategory,
                0.000001
        );
    }


    @Test
    void shouldRejectUnrelatedSemanticNoiseBelowThreshold() {
        KnowledgeHybridScorer scorer =
                new KnowledgeHybridScorer(
                        256,
                        2.0,
                        0.95
                );

        KnowledgeSearchResult result = scorer.score(
                document(
                        "unrelated",
                        "火星天气观测",
                        "记录行星表面的气象变化",
                        List.of("天文")
                ),
                "退款什么时候到账",
                KnowledgeCategory.BILLING
        );

        assertEquals(0.0, result.score(), 0.000001);
        assertTrue(result.matchedKeywords().isEmpty());
    }


    @Test
    void shouldRejectInvalidConfiguration() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new KnowledgeHybridScorer(
                                0,
                                2.0,
                                0.2
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new KnowledgeHybridScorer(
                                256,
                                -0.1,
                                0.2
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new KnowledgeHybridScorer(
                                256,
                                2.0,
                                1.1
                        )
                )
        );
    }


    private KnowledgeHybridScorer scorer() {
        return new KnowledgeHybridScorer(
                256,
                2.0,
                0.12
        );
    }


    private KnowledgeDocument document(
            String id,
            String title,
            String content,
            List<String> keywords
    ) {
        return new KnowledgeDocument(
                id,
                title,
                KnowledgeCategory.BILLING,
                content,
                keywords
        );
    }
}
