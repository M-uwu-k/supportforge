package com.supportforge.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


class KnowledgeRerankerTest {


    @Test
    void shouldPromoteExactOriginalPhraseWithoutChangingStoredScore() {
        KnowledgeReranker reranker = reranker();

        KnowledgeSearchResult generic = result(
                "generic",
                "通用账户帮助",
                "常见账户问题说明",
                2.0,
                List.of()
        );

        KnowledgeSearchResult relevant = result(
                "relevant",
                "双重验证操作指南",
                "介绍如何开启双重验证",
                1.4,
                List.of()
        );

        List<KnowledgeSearchResult> results = reranker.rerank(
                "双重验证",
                List.of(generic, relevant),
                2
        );

        assertEquals("relevant", results.getFirst().document().id());
        assertEquals(1.4, results.getFirst().score(), 0.000001);
    }


    @Test
    void shouldUseMatchedKeywordCoverageAsSecondaryEvidence() {
        KnowledgeReranker reranker = reranker();

        KnowledgeSearchResult oneKeyword = result(
                "one",
                "文档一",
                "普通说明",
                2.0,
                List.of("退款")
        );

        KnowledgeSearchResult threeKeywords = result(
                "three",
                "文档二",
                "普通说明",
                2.0,
                List.of("退款", "订单", "审核")
        );

        List<KnowledgeSearchResult> results = reranker.rerank(
                "怎么处理",
                List.of(oneKeyword, threeKeywords),
                2
        );

        assertEquals("three", results.getFirst().document().id());
    }


    @Test
    void shouldKeepStrongBaseScoreWhenRelevanceBonusCannotOvertakeIt() {
        KnowledgeReranker reranker = reranker();

        KnowledgeSearchResult strong = result(
                "strong",
                "通用规则",
                "普通内容",
                5.0,
                List.of()
        );

        KnowledgeSearchResult phraseMatch = result(
                "phrase",
                "退款到账说明",
                "退款到账需要等待",
                1.0,
                List.of("退款")
        );

        List<KnowledgeSearchResult> results = reranker.rerank(
                "退款到账",
                List.of(strong, phraseMatch),
                2
        );

        assertEquals("strong", results.getFirst().document().id());
    }


    @Test
    void shouldApplyLimitAndUseDocumentIdForStableTies() {
        KnowledgeReranker reranker = reranker();

        List<KnowledgeSearchResult> results = reranker.rerank(
                "",
                List.of(
                        result("b-doc", "B", "", 2.0, List.of()),
                        result("a-doc", "A", "", 2.0, List.of()),
                        result("c-doc", "C", "", 1.0, List.of())
                ),
                2
        );

        assertEquals(2, results.size());
        assertEquals("a-doc", results.get(0).document().id());
        assertEquals("b-doc", results.get(1).document().id());
    }


    @Test
    void shouldRejectInvalidConfiguration() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new KnowledgeReranker(-0.1, 0.8, 0.15)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new KnowledgeReranker(1.0, -0.1, 0.15)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new KnowledgeReranker(1.0, 0.8, -0.1)
                )
        );
    }


    private KnowledgeReranker reranker() {
        return new KnowledgeReranker(
                1.0,
                0.8,
                0.15
        );
    }


    private KnowledgeSearchResult result(
            String id,
            String title,
            String content,
            double score,
            List<String> matchedKeywords
    ) {
        return new KnowledgeSearchResult(
                new KnowledgeDocument(
                        id,
                        title,
                        KnowledgeCategory.GENERAL,
                        content,
                        List.of()
                ),
                score,
                matchedKeywords
        );
    }
}
