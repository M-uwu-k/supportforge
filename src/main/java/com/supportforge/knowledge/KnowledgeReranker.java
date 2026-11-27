package com.supportforge.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;


@Component
public class KnowledgeReranker {


    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+");

    private final double exactPhraseBonus;
    private final double titleOverlapWeight;
    private final double keywordCoverageWeight;


    public KnowledgeReranker(
            @Value(
                    "${supportforge.knowledge.retrieval.rerank.exact-phrase-bonus:1.0}"
            )
            double exactPhraseBonus,

            @Value(
                    "${supportforge.knowledge.retrieval.rerank.title-overlap-weight:0.8}"
            )
            double titleOverlapWeight,

            @Value(
                    "${supportforge.knowledge.retrieval.rerank.keyword-coverage-weight:0.15}"
            )
            double keywordCoverageWeight
    ) {

        validateNonNegative(
                exactPhraseBonus,
                "exactPhraseBonus"
        );
        validateNonNegative(
                titleOverlapWeight,
                "titleOverlapWeight"
        );
        validateNonNegative(
                keywordCoverageWeight,
                "keywordCoverageWeight"
        );


        this.exactPhraseBonus = exactPhraseBonus;
        this.titleOverlapWeight = titleOverlapWeight;
        this.keywordCoverageWeight =
                keywordCoverageWeight;
    }


    public static KnowledgeReranker defaults() {
        return new KnowledgeReranker(
                1.0,
                0.8,
                0.15
        );
    }

    public List<KnowledgeSearchResult> rerank(
            String originalQuery,
            List<KnowledgeSearchResult> candidates,
            int limit
    ) {

        if (candidates == null
                || candidates.isEmpty()) {
            return List.of();
        }


        int safeLimit =
                Math.max(
                        1,
                        Math.min(limit, 10)
                );


        String normalizedQuery =
                normalize(originalQuery);


        return candidates.stream()

                .filter(result ->
                        result != null
                                && result.document() != null
                                && result.document().id() != null
                                && !result.document().id().isBlank()
                )

                .sorted(
                        Comparator
                                .comparingDouble(
                                        (KnowledgeSearchResult result) ->
                                                rankingScore(
                                                        normalizedQuery,
                                                        result
                                                )
                                )
                                .reversed()

                                .thenComparing(
                                        result ->
                                                result.document().id()
                                )
                )
                .limit(safeLimit)
                .toList();
    }


    private double rankingScore(
            String normalizedQuery,
            KnowledgeSearchResult result
    ) {
        KnowledgeDocument document =
                result.document();


        String title =
                normalize(document.title());

        String content =
                normalize(document.content());


        String documentText =
                title + " " + content;


        double score =
                result.score();


        if (!normalizedQuery.isBlank()
                && documentText.contains(
                normalizedQuery
        )) {
            score += exactPhraseBonus;
        }


        score += ngramCoverage(
                normalizedQuery,
                title
        ) * titleOverlapWeight;


        long matchedKeywordCount =
                result.matchedKeywords()
                        .stream()
                        .filter(keyword ->
                                keyword != null
                                        && !keyword.isBlank()
                        )
                        .distinct()
                        .limit(5)
                        .count();

        score += matchedKeywordCount
                * keywordCoverageWeight;

        return score;
    }


    private double ngramCoverage(
            String query,
            String title
    ) {

        String normalizedQuery =
                query.replace(" ", "");

        String normalizedTitle =
                title.replace(" ", "");


        if (normalizedQuery.isBlank()
                || normalizedTitle.isBlank()) {
            return 0.0;
        }


        int gramSize =
                normalizedQuery.length() >= 2
                        ? 2
                        : 1;


        Set<String> queryGrams =
                grams(
                        normalizedQuery,
                        gramSize
                );

        if (queryGrams.isEmpty()) {
            return 0.0;
        }


        long matched =
                queryGrams.stream()
                        .filter(
                                normalizedTitle::contains
                        )
                        .count();


        return matched
                / (double) queryGrams.size();
    }

    private Set<String> grams(
            String text,
            int gramSize
    ) {

        Set<String> values =
                new LinkedHashSet<>();


        for (int start = 0;
             start + gramSize <= text.length();
             start++) {

            values.add(
                    text.substring(
                            start,
                            start + gramSize
                    )
            );
        }

        return values;
    }


    private String normalize(
            String value
    ) {
        if (value == null || value.isBlank()) {
            return "";
        }

        return WHITESPACE
                .matcher(
                        value.strip()
                                .toLowerCase(
                                        Locale.ROOT
                                )
                )
                .replaceAll(" ");
    }


    private void validateNonNegative(
            double value,
            String name
    ) {
        if (!Double.isFinite(value)
                || value < 0.0) {
            throw new IllegalArgumentException(
                    name + "必须是非负有限数"
            );
        }
    }
}
