package com.supportforge.knowledge;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;


@Component
public class MultiQueryKnowledgeRetriever {

    private final KnowledgeBase knowledgeBase;
    private final KnowledgeQueryRewriter queryRewriter;
    private final Executor executor;

    private final KnowledgeReranker reranker;


    @Autowired
    public MultiQueryKnowledgeRetriever(
            KnowledgeBase knowledgeBase,
            KnowledgeQueryRewriter queryRewriter,
            @Qualifier("agentExecutor")
            Executor executor,
            KnowledgeReranker reranker
    ) {
        this.knowledgeBase = knowledgeBase;
        this.queryRewriter = queryRewriter;
        this.executor = executor;
        this.reranker = reranker;
    }


    public MultiQueryKnowledgeRetriever(
            KnowledgeBase knowledgeBase,
            KnowledgeQueryRewriter queryRewriter,
            Executor executor
    ) {
        this(
                knowledgeBase,
                queryRewriter,
                executor,
                KnowledgeReranker.defaults()
        );
    }


    public List<KnowledgeSearchResult> search(
            String query,
            KnowledgeCategory category,
            int limit
    ) {

        if (query == null || query.isBlank()) {
            return List.of();
        }


        int safeLimit =
                Math.max(
                        1,
                        Math.min(limit, 10)
                );


        var callerIdentity = com.supportforge.security.TenantContext.current().orElse(null);
        List<String> rewrittenQueries =
                queryRewriter.rewrite(
                        query,
                        category
                );

        if (rewrittenQueries.isEmpty()) {

            return List.of();
        }


        List<CompletableFuture<SearchAttempt>>
                futures =
                rewrittenQueries.stream()

                        .filter(rewritten ->
                                rewritten != null
                                        && !rewritten.isBlank()
                        )
                        .distinct()
                        .map(rewritten ->
                                CompletableFuture.supplyAsync(
                                        () -> com.supportforge.security.TenantContext.callWith(callerIdentity, () -> safeSearch(
                                                rewritten,
                                                category,
                                                safeLimit
                                        )),
                                        executor
                                )
                        )
                        .toList();

        if (futures.isEmpty()) {

            return List.of();
        }


        List<SearchAttempt> attempts =
                futures.stream()
                        .map(CompletableFuture::join)
                        .toList();


        boolean allFailed =
                attempts.stream()
                        .allMatch(
                                SearchAttempt::failed
                        );

        List<KnowledgeSearchResult> allResults;

        if (allFailed) {

            allResults =
                    fallbackSearch(
                            query,
                            category,
                            safeLimit
                    );
        } else {


            allResults =
                    attempts.stream()
                            .flatMap(attempt ->
                                    attempt.results()
                                            .stream()
                            )
                            .toList();
        }


        List<KnowledgeSearchResult> merged =
                mergeResults(allResults);


        return reranker.rerank(
                query,
                merged,
                safeLimit
        );
    }


    private SearchAttempt safeSearch(
            String query,
            KnowledgeCategory category,
            int limit
    ) {
        try {
            List<KnowledgeSearchResult> results =
                    knowledgeBase.search(
                            query,
                            category,
                            limit
                    );


            return SearchAttempt.success(
                    results == null
                            ? List.of()
                            : results
            );
        } catch (RuntimeException exception) {
            return SearchAttempt.failure();
        }
    }


    private List<KnowledgeSearchResult> fallbackSearch(
            String originalQuery,
            KnowledgeCategory category,
            int limit
    ) {
        try {
            List<KnowledgeSearchResult> results =
                    knowledgeBase.search(
                            originalQuery,
                            category,
                            limit
                    );

            return results == null
                    ? List.of()
                    : results;
        } catch (RuntimeException exception) {


            return List.of();
        }
    }


    private List<KnowledgeSearchResult> mergeResults(
            List<KnowledgeSearchResult> results
    ) {
        Map<String, KnowledgeSearchResult> byDocumentId =
                new LinkedHashMap<>();

        for (KnowledgeSearchResult result : results) {
            if (result == null
                    || result.document() == null
                    || result.document().id() == null
                    || result.document().id().isBlank()) {
                continue;
            }

            byDocumentId.merge(
                    result.document().id(),
                    result,
                    this::mergeSameDocument
            );
        }


        return List.copyOf(
                byDocumentId.values()
        );
    }


    private KnowledgeSearchResult mergeSameDocument(
            KnowledgeSearchResult current,
            KnowledgeSearchResult incoming
    ) {

        Set<String> mergedKeywords =
                new LinkedHashSet<>();

        mergedKeywords.addAll(
                safeKeywords(
                        current.matchedKeywords()
                )
        );

        mergedKeywords.addAll(
                safeKeywords(
                        incoming.matchedKeywords()
                )
        );


        KnowledgeSearchResult bestResult =
                incoming.score() > current.score()
                        ? incoming
                        : current;


        return new KnowledgeSearchResult(
                bestResult.document(),
                Math.max(
                        current.score(),
                        incoming.score()
                ),
                new ArrayList<>(
                        mergedKeywords
                )
        );
    }


    private List<String> safeKeywords(
            List<String> keywords
    ) {
        if (keywords == null) {
            return List.of();
        }

        return keywords.stream()
                .filter(keyword ->
                        keyword != null
                                && !keyword.isBlank()
                )
                .toList();
    }


    private record SearchAttempt(
            List<KnowledgeSearchResult> results,
            boolean failed
    ) {
        private SearchAttempt {


            results = List.copyOf(results);
        }


        static SearchAttempt success(
                List<KnowledgeSearchResult> results
        ) {
            return new SearchAttempt(
                    results,
                    false
            );
        }


        static SearchAttempt failure() {
            return new SearchAttempt(
                    List.of(),
                    true
            );
        }
    }
}
