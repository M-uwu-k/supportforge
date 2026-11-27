package com.supportforge.knowledge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class MultiQueryKnowledgeRetrieverTest {

    private static final Executor SAME_THREAD = Runnable::run;


    @Test
    void shouldReturnEmptyWithoutSearchingForBlankQuery() {
        StubKnowledgeBase knowledgeBase =
                new StubKnowledgeBase();

        MultiQueryKnowledgeRetriever retriever =
                retriever(
                        knowledgeBase,
                        List.of("不会被使用")
                );

        assertEquals(
                List.of(),
                retriever.search(
                        "   ",
                        KnowledgeCategory.GENERAL,
                        3
                )
        );
        assertTrue(knowledgeBase.calls.isEmpty());
    }


    @Test
    void shouldSearchEveryRewrittenQuery() {
        StubKnowledgeBase knowledgeBase =
                new StubKnowledgeBase();

        knowledgeBase.answer(
                "原问题",
                result("doc-1", 1.0, "原词")
        );
        knowledgeBase.answer(
                "改写一",
                result("doc-2", 2.0, "改写词一")
        );
        knowledgeBase.answer(
                "改写二",
                result("doc-3", 3.0, "改写词二")
        );

        MultiQueryKnowledgeRetriever retriever =
                retriever(
                        knowledgeBase,
                        List.of(
                                "原问题",
                                "改写一",
                                "改写二"
                        )
                );

        List<KnowledgeSearchResult> results =
                retriever.search(
                        "原问题",
                        KnowledgeCategory.GENERAL,
                        5
                );

        assertEquals(
                List.of(
                        "原问题",
                        "改写一",
                        "改写二"
                ),
                knowledgeBase.calls
        );
        assertEquals(3, results.size());
        assertEquals("doc-3", results.getFirst().document().id());
    }


    @Test
    void shouldMergeSameDocumentAndKeepHighestScore() {
        StubKnowledgeBase knowledgeBase =
                new StubKnowledgeBase();

        knowledgeBase.answer(
                "原问题",
                result("same-doc", 1.2, "登录")
        );
        knowledgeBase.answer(
                "标准表达",
                result("same-doc", 3.4, "401", "凭证")
        );

        MultiQueryKnowledgeRetriever retriever =
                retriever(
                        knowledgeBase,
                        List.of(
                                "原问题",
                                "标准表达"
                        )
                );

        List<KnowledgeSearchResult> results =
                retriever.search(
                        "原问题",
                        KnowledgeCategory.TECHNICAL,
                        5
                );

        assertEquals(1, results.size());
        assertEquals(3.4, results.getFirst().score(), 0.000001);
        assertEquals(
                List.of(
                        "登录",
                        "401",
                        "凭证"
                ),
                results.getFirst().matchedKeywords()
        );
    }


    @Test
    void shouldSortByScoreAndApplyFinalLimit() {
        StubKnowledgeBase knowledgeBase =
                new StubKnowledgeBase();

        knowledgeBase.answer(
                "q1",
                result("doc-low", 0.8, "低"),
                result("doc-high", 4.0, "高")
        );
        knowledgeBase.answer(
                "q2",
                result("doc-middle", 2.0, "中")
        );

        MultiQueryKnowledgeRetriever retriever =
                retriever(
                        knowledgeBase,
                        List.of("q1", "q2")
                );

        List<KnowledgeSearchResult> results =
                retriever.search(
                        "q1",
                        null,
                        2
                );

        assertEquals(2, results.size());
        assertEquals("doc-high", results.get(0).document().id());
        assertEquals("doc-middle", results.get(1).document().id());
    }


    @Test
    void shouldKeepOtherResultsWhenOneQueryFails() {
        StubKnowledgeBase knowledgeBase =
                new StubKnowledgeBase();

        knowledgeBase.fail("失败查询");
        knowledgeBase.answer(
                "成功查询",
                result("doc-ok", 2.5, "成功")
        );

        MultiQueryKnowledgeRetriever retriever =
                retriever(
                        knowledgeBase,
                        List.of(
                                "失败查询",
                                "成功查询"
                        )
                );

        List<KnowledgeSearchResult> results =
                retriever.search(
                        "原问题",
                        KnowledgeCategory.GENERAL,
                        3
                );

        assertEquals(1, results.size());
        assertEquals("doc-ok", results.getFirst().document().id());
        assertEquals(
                List.of(
                        "失败查询",
                        "成功查询"
                ),
                knowledgeBase.calls
        );
    }


    @Test
    void shouldRerankMergedResultsUsingOriginalQuery() {
        StubKnowledgeBase knowledgeBase =
                new StubKnowledgeBase();

        knowledgeBase.answer(
                "q1",
                resultWithText(
                        "generic",
                        "通用帮助",
                        "普通账户说明",
                        2.0
                )
        );
        knowledgeBase.answer(
                "q2",
                resultWithText(
                        "relevant",
                        "双重验证操作指南",
                        "介绍如何开启双重验证",
                        1.4
                )
        );

        MultiQueryKnowledgeRetriever retriever =
                retriever(
                        knowledgeBase,
                        List.of("q1", "q2")
                );

        List<KnowledgeSearchResult> results =
                retriever.search(
                        "双重验证",
                        KnowledgeCategory.TECHNICAL,
                        2
                );

        assertEquals(
                "relevant",
                results.getFirst().document().id()
        );
    }


    @Test
    void shouldRetryOriginalQueryWhenAllParallelSearchesFail() {
        StubKnowledgeBase knowledgeBase =
                new StubKnowledgeBase();

        knowledgeBase.answer(
                "原问题",
                result("fallback-doc", 2.2, "原词")
        );
        knowledgeBase.failNext("原问题", 1);
        knowledgeBase.failNext("改写查询", 1);

        MultiQueryKnowledgeRetriever retriever =
                retriever(
                        knowledgeBase,
                        List.of(
                                "原问题",
                                "改写查询"
                        )
                );

        List<KnowledgeSearchResult> results =
                retriever.search(
                        "原问题",
                        KnowledgeCategory.GENERAL,
                        3
                );

        assertEquals(1, results.size());
        assertEquals(
                "fallback-doc",
                results.getFirst().document().id()
        );
        assertEquals(
                List.of(
                        "原问题",
                        "改写查询",
                        "原问题"
                ),
                knowledgeBase.calls
        );
    }


    private MultiQueryKnowledgeRetriever retriever(
            KnowledgeBase knowledgeBase,
            List<String> rewrittenQueries
    ) {
        KnowledgeQueryRewriter rewriter =
                new KnowledgeQueryRewriter(10) {
                    @Override
                    public List<String> rewrite(
                            String query,
                            KnowledgeCategory category
                    ) {
                        return List.copyOf(rewrittenQueries);
                    }
                };

        return new MultiQueryKnowledgeRetriever(
                knowledgeBase,
                rewriter,
                SAME_THREAD
        );
    }


    private KnowledgeSearchResult result(
            String id,
            double score,
            String... matchedKeywords
    ) {
        return new KnowledgeSearchResult(
                new KnowledgeDocument(
                        id,
                        "标题-" + id,
                        KnowledgeCategory.GENERAL,
                        "正文-" + id,
                        List.of(matchedKeywords)
                ),
                score,
                List.of(matchedKeywords)
        );
    }


    private KnowledgeSearchResult resultWithText(
            String id,
            String title,
            String content,
            double score
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
                List.of()
        );
    }


    private static final class StubKnowledgeBase
            implements KnowledgeBase {

        @Override
        public List<KnowledgeDocument> readableDocuments() { return List.of(); }

        private final Map<String, List<KnowledgeSearchResult>> answers =
                new LinkedHashMap<>();

        private final Set<String> failedQueries =
                new java.util.HashSet<>();


        private final Map<String, Integer> failuresRemaining =
                new LinkedHashMap<>();

        private final List<String> calls =
                Collections.synchronizedList(
                        new ArrayList<>()
                );

        void answer(
                String query,
                KnowledgeSearchResult... results
        ) {
            answers.put(
                    query,
                    List.of(results)
            );
        }

        void fail(String query) {
            failedQueries.add(query);
        }


        void failNext(
                String query,
                int times
        ) {
            failuresRemaining.put(
                    query,
                    times
            );
        }

        @Override
        public List<KnowledgeSearchResult> search(
                String query,
                KnowledgeCategory category,
                int limit
        ) {
            calls.add(query);


            int remaining =
                    failuresRemaining.getOrDefault(
                            query,
                            0
                    );

            if (remaining > 0) {

                failuresRemaining.put(
                        query,
                        remaining - 1
                );

                throw new IllegalStateException(
                        "模拟暂时性检索失败"
                );
            }

            if (failedQueries.contains(query)) {
                throw new IllegalStateException(
                        "模拟单路检索失败"
                );
            }

            return answers.getOrDefault(
                    query,
                    List.of()
            );
        }

        @Override
        public KnowledgeDocument add(
                KnowledgeDocument document
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KnowledgeDocument delete(
                String documentId
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int count() {
            return 0;
        }
    }
}
