package com.supportforge.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;


@Component
public class KnowledgeHybridScorer {


    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+");

    private final int dimensions;
    private final double semanticWeight;
    private final double minimumSemanticScore;


    public KnowledgeHybridScorer(
            @Value(
                    "${supportforge.knowledge.retrieval.semantic-dimensions:256}"
            )
            int dimensions,

            @Value(
                    "${supportforge.knowledge.retrieval.semantic-weight:2.0}"
            )
            double semanticWeight,

            @Value(
                    "${supportforge.knowledge.retrieval.minimum-semantic-score:0.45}"
            )
            double minimumSemanticScore
    ) {

        if (dimensions < 1) {
            throw new IllegalArgumentException(
                    "semantic dimensions必须至少为1"
            );
        }


        if (!Double.isFinite(semanticWeight)
                || semanticWeight < 0.0) {
            throw new IllegalArgumentException(
                    "semantic weight必须是非负有限数"
            );
        }


        if (!Double.isFinite(minimumSemanticScore)
                || minimumSemanticScore < 0.0
                || minimumSemanticScore > 1.0) {
            throw new IllegalArgumentException(
                    "minimum semantic score必须位于0到1之间"
            );
        }


        this.dimensions = dimensions;
        this.semanticWeight = semanticWeight;
        this.minimumSemanticScore =
                minimumSemanticScore;
    }


    public static KnowledgeHybridScorer defaults() {
        return new KnowledgeHybridScorer(
                256,
                2.0,
                0.45
        );
    }

    public KnowledgeSearchResult score(
            KnowledgeDocument document,
            String query,
            KnowledgeCategory requestedCategory
    ) {

        String normalizedQuery =
                normalize(query);


        List<String> matchedKeywords =
                findMatchedKeywords(
                        document,
                        normalizedQuery
                );


        double keywordScore =
                calculateKeywordScore(
                        matchedKeywords
                );


        String documentText =
                buildDocumentText(document);


        double semanticSimilarity =
                cosineSimilarity(
                        embed(normalizedQuery),
                        embed(documentText)
                );


        double acceptedSemanticScore =
                semanticSimilarity
                        >= minimumSemanticScore
                        ? semanticSimilarity
                        : 0.0;


        if (keywordScore == 0.0
                && acceptedSemanticScore == 0.0) {
            return new KnowledgeSearchResult(
                    document,
                    0.0,
                    List.of()
            );
        }


        double categoryBoost =
                requestedCategory != null
                        && document.category()
                        == requestedCategory
                        ? 0.5
                        : 0.0;


        double finalScore =
                keywordScore
                        + acceptedSemanticScore
                        * semanticWeight
                        + categoryBoost;


        return new KnowledgeSearchResult(
                document,
                finalScore,
                matchedKeywords
        );
    }

    private List<String> findMatchedKeywords(
            KnowledgeDocument document,
            String normalizedQuery
    ) {

        if (normalizedQuery.isBlank()) {
            return List.of();
        }


        return document.keywords()
                .stream()

                .filter(keyword ->
                        keyword != null
                                && !keyword.isBlank()
                )


                .filter(keyword ->
                        normalizedQuery.contains(
                                keyword.toLowerCase(
                                        Locale.ROOT
                                )
                        )
                )
                .distinct()
                .toList();
    }


    private double calculateKeywordScore(
            List<String> matchedKeywords
    ) {
        return matchedKeywords.stream()
                .mapToDouble(keyword ->
                        1.0 + Math.min(
                                keyword.length() / 10.0,
                                0.5
                        )
                )
                .sum();
    }


    private String buildDocumentText(
            KnowledgeDocument document
    ) {
        return normalize(
                safe(document.title())
                        + " "
                        + safe(document.content())
                        + " "
                        + String.join(
                        " ",
                        document.keywords()
                )
        );
    }


    private double[] embed(
            String text
    ) {

        double[] vector =
                new double[dimensions];


        String normalized =
                normalize(text)
                        .replace(" ", "");

        if (normalized.isBlank()) {
            return vector;
        }


        int maximumGram =
                Math.min(
                        3,
                        normalized.length()
                );


        for (int gramSize = 1;
             gramSize <= maximumGram;
             gramSize++) {


            for (int start = 0;
                 start + gramSize
                         <= normalized.length();
                 start++) {


                String gram =
                        normalized.substring(
                                start,
                                start + gramSize
                        );


                int hash =
                        gram.hashCode();


                int index =
                        Math.floorMod(
                                hash,
                                dimensions
                        );


                int signHash =
                        (gram + "#sign")
                                .hashCode();


                double sign =
                        (signHash & 1) == 0
                                ? 1.0
                                : -1.0;


                vector[index] +=
                        sign * gramSize;
            }
        }


        normalizeVector(vector);

        return vector;
    }

    private void normalizeVector(
            double[] vector
    ) {

        double squaredSum = 0.0;

        for (double value : vector) {
            squaredSum += value * value;
        }

        if (squaredSum == 0.0) {
            return;
        }


        double length =
                Math.sqrt(squaredSum);

        for (int index = 0;
             index < vector.length;
             index++) {

            vector[index] /= length;
        }
    }

    private double cosineSimilarity(
            double[] left,
            double[] right
    ) {

        double dotProduct = 0.0;

        for (int index = 0;
             index < left.length;
             index++) {


            dotProduct +=
                    left[index] * right[index];
        }


        return Math.max(
                0.0,
                Math.min(1.0, dotProduct)
        );
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

    private String safe(String value) {

        return value == null
                ? ""
                : value;
    }
}
