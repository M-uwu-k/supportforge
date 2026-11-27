package com.supportforge.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryKnowledgeBaseTest {

    private InMemoryKnowledgeBase knowledgeBase;

    @BeforeEach
    void setUp() {

        knowledgeBase =
                new InMemoryKnowledgeBase();
    }

    @Test
    void shouldFindTechnicalLoginKnowledge() {
        List<KnowledgeSearchResult> results =
                knowledgeBase.search(
                        "登录时一直报错401",
                        KnowledgeCategory.TECHNICAL,
                        3
                );

        assertFalse(results.isEmpty());

        KnowledgeSearchResult first =
                results.get(0);

        assertAll(
                () -> assertEquals(
                        "tech-login-401",
                        first.document().id()
                ),

                () -> assertEquals(
                        KnowledgeCategory.TECHNICAL,
                        first.document().category()
                ),

                () -> assertTrue(
                        first.score() > 0
                ),

                () -> assertTrue(
                        first.matchedKeywords()
                                .contains("401")
                ),

                () -> assertTrue(
                        first.matchedKeywords()
                                .contains("登录")
                )
        );
    }

    @Test
    void shouldRankDuplicateChargeBeforeRefund() {
        List<KnowledgeSearchResult> results =
                knowledgeBase.search(
                        "订单A12345重复扣款99元，我要退款",
                        KnowledgeCategory.BILLING,
                        3
                );


        assertEquals(3, results.size());

        assertAll(
                () -> assertEquals(
                        "billing-duplicate-charge",
                        results.get(0)
                                .document()
                                .id()
                ),

                () -> assertEquals(
                        "billing-refund",
                        results.get(1)
                                .document()
                                .id()
                ),

                () -> assertTrue(
                        results.get(0).score()
                                > results.get(1).score()
                ),

                () -> assertTrue(
                        results.get(1).score()
                                > results.get(2).score()
                )
        );
    }

    @Test
    void shouldRespectCategoryFilter() {
        List<KnowledgeSearchResult> results =
                knowledgeBase.search(
                        "我要申请退款",
                        KnowledgeCategory.TECHNICAL,
                        3
                );


        assertTrue(
                results.stream()
                        .allMatch(result ->
                                result.document().category()
                                        == KnowledgeCategory.TECHNICAL
                                        || result.document().category()
                                        == KnowledgeCategory.GENERAL
                        )
        );
    }

    @Test
    void shouldReturnEmptyForUnrelatedQuestion() {
        List<KnowledgeSearchResult> results =
                knowledgeBase.search(
                        "今天天气怎么样",
                        null,
                        3
                );

        assertTrue(results.isEmpty());
    }

    @Test
    void shouldReturnEmptyForBlankQuery() {
        assertAll(
                () -> assertTrue(
                        knowledgeBase.search(
                                "",
                                KnowledgeCategory.BILLING,
                                3
                        ).isEmpty()
                ),

                () -> assertTrue(
                        knowledgeBase.search(
                                "   ",
                                KnowledgeCategory.BILLING,
                                3
                        ).isEmpty()
                ),

                () -> assertTrue(
                        knowledgeBase.search(
                                null,
                                KnowledgeCategory.BILLING,
                                3
                        ).isEmpty()
                )
        );
    }

    @Test
    void shouldLimitResultCount() {
        List<KnowledgeSearchResult> results =
                knowledgeBase.search(
                        "订单物流退款扣款发票登录401崩溃500",
                        null,
                        2
                );

        assertTrue(results.size() <= 2);
    }

    @Test
    void scoresShouldBeDescending() {
        List<KnowledgeSearchResult> results =
                knowledgeBase.search(
                        "重复扣款退款订单",
                        KnowledgeCategory.BILLING,
                        10
                );

        for (int index = 1;
             index < results.size();
             index++) {

            double previousScore =
                    results.get(index - 1).score();

            double currentScore =
                    results.get(index).score();

            assertTrue(
                    previousScore >= currentScore,
                    "检索结果没有按照分数降序排列"
            );
        }
    }

    @Test
    void shouldAddCountAndSearchRuntimeDocument() {
        int before = knowledgeBase.count();

        KnowledgeDocument document = new KnowledgeDocument(
                "runtime-refund-policy",
                "运行时退款规则",
                KnowledgeCategory.BILLING,
                "运行时添加的退款资料。",
                List.of("运行时退款")
        );

        KnowledgeDocument added = knowledgeBase.add(document);
        List<KnowledgeSearchResult> results = knowledgeBase.search(
                "运行时退款",
                KnowledgeCategory.BILLING,
                3
        );

        assertAll(
                () -> assertEquals(document, added),
                () -> assertEquals(before + 1, knowledgeBase.count()),
                () -> assertFalse(results.isEmpty()),
                () -> assertEquals(
                        "runtime-refund-policy",
                        results.get(0).document().id()
                )
        );
    }

    @Test
    void shouldRejectDuplicateDocumentId() {
        KnowledgeDocument document = new KnowledgeDocument(
                "duplicate-id",
                "第一篇文档",
                KnowledgeCategory.GENERAL,
                "测试内容",
                List.of("测试")
        );

        knowledgeBase.add(document);

        KnowledgeConflictException exception = assertThrows(
                KnowledgeConflictException.class,
                () -> knowledgeBase.add(
                        new KnowledgeDocument(
                                "duplicate-id",
                                "第二篇文档",
                                KnowledgeCategory.BILLING,
                                "不同内容",
                                List.of("不同")
                        )
                )
        );

        assertTrue(exception.getMessage().contains("ID已存在"));
    }

    @Test
    void shouldUpsertDynamicDocumentIdempotently() {
        KnowledgeDocument first = new KnowledgeDocument(
                "upsert-document",
                "初始标题",
                KnowledgeCategory.GENERAL,
                "初始内容",
                List.of("初始")
        );
        KnowledgeDocument updated = new KnowledgeDocument(
                "upsert-document",
                "更新标题",
                KnowledgeCategory.TECHNICAL,
                "更新内容",
                List.of("更新")
        );
        int before = knowledgeBase.count();

        knowledgeBase.upsert(first);
        knowledgeBase.upsert(first);
        KnowledgeDocument result = knowledgeBase.upsert(updated);

        assertEquals(updated, result);
        assertEquals(before + 1, knowledgeBase.count());
        assertEquals(
                "更新内容",
                knowledgeBase.search(
                                "更新内容",
                                KnowledgeCategory.TECHNICAL,
                                1
                        )
                        .getFirst()
                        .document()
                        .content()
        );
    }


    @Test
    void shouldFindDocumentByTitleAndContentWithoutKeywordHit() {
        knowledgeBase.add(
                new KnowledgeDocument(
                        "semantic-two-factor",
                        "账户双重验证",
                        KnowledgeCategory.TECHNICAL,
                        "双重验证可以提升账户安全",
                        List.of("二次校验")
                )
        );

        List<KnowledgeSearchResult> results =
                knowledgeBase.search(
                        "双重验证可以提升账户安全",
                        KnowledgeCategory.TECHNICAL,
                        3
                );

        assertFalse(results.isEmpty());
        assertEquals(
                "semantic-two-factor",
                results.getFirst().document().id()
        );
        assertTrue(
                results.getFirst()
                        .matchedKeywords()
                        .isEmpty()
        );
    }
}
