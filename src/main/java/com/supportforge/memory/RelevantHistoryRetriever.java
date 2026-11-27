package com.supportforge.memory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Component
public class RelevantHistoryRetriever {


    private static final Pattern ALPHANUMERIC_PATTERN =
            Pattern.compile(
                    "[a-z0-9][a-z0-9_-]{1,}"
            );


    private static final Pattern CHINESE_PATTERN =
            Pattern.compile(
                    "[\\u4e00-\\u9fff]+"
            );

    private final boolean enabled;
    private final int maxResults;
    private final int maxItemChars;
    private final double minScore;


    public RelevantHistoryRetriever(
            @Value(
                    "${supportforge.memory.history.enabled:true}"
            )
            boolean enabled,

            @Value(
                    "${supportforge.memory.history.max-results:3}"
            )
            int maxResults,

            @Value(
                    "${supportforge.memory.history.max-item-chars:800}"
            )
            int maxItemChars,

            @Value(
                    "${supportforge.memory.history.min-score:1.0}"
            )
            double minScore
    ) {
        if (maxResults < 1) {
            throw new IllegalArgumentException(
                    "相关历史最大返回数量必须大于0"
            );
        }

        if (maxItemChars < 1) {
            throw new IllegalArgumentException(
                    "单条相关历史最大字符数必须大于0"
            );
        }

        if (minScore < 0) {
            throw new IllegalArgumentException(
                    "相关历史最低分数不能小于0"
            );
        }

        this.enabled = enabled;
        this.maxResults = maxResults;
        this.maxItemChars = maxItemChars;
        this.minScore = minScore;
    }


    public static RelevantHistoryRetriever defaults() {
        return new RelevantHistoryRetriever(
                true,
                3,
                800,
                1.0
        );
    }


    public List<String> retrieve(
            MemorySnapshot snapshot,
            String userId,
            String currentConversationId,
            String query
    ) {

        if (!enabled
                || snapshot == null
                || query == null
                || query.isBlank()) {

            return List.of();
        }


        ConversationKey currentKey =
                new ConversationKey(
                        userId,
                        currentConversationId
                );


        Set<String> queryTokens =
                tokenize(query);


        String compactQuery =
                compact(query);

        if (queryTokens.isEmpty()
                && compactQuery.isBlank()) {

            return List.of();
        }


        List<ScoredHistory> matches =
                new ArrayList<>();


        for (MemoryConversationState state :
                snapshot.conversations()) {

            ConversationKey candidateKey =
                    new ConversationKey(
                            state.userId(),
                            state.conversationId()
                    );


            if (!candidateKey.userId()
                    .equals(currentKey.userId())) {


                continue;
            }


            if (candidateKey.equals(currentKey)) {

                continue;
            }


            String searchableText =
                    searchableText(state);


            double score =
                    calculateScore(
                            queryTokens,
                            compactQuery,
                            searchableText
                    );

            if (score < minScore) {

                continue;
            }


            matches.add(
                    new ScoredHistory(
                            promptText(state),
                            score,
                            state.updatedAt()
                    )
            );


        }


        return matches.stream()
                .sorted(
                        Comparator
                                .comparingDouble(
                                        ScoredHistory::score
                                )
                                .reversed()
                                .thenComparing(
                                        ScoredHistory::updatedAt,
                                        Comparator.reverseOrder()
                                )
                )
                .limit(maxResults)

                .map(ScoredHistory::text)
                .toList();
    }


    private String searchableText(
            MemoryConversationState state
    ) {
        StringBuilder text =
                new StringBuilder();

        if (!state.summary().isBlank()) {
            text.append(state.summary())
                    .append("\n");
        }

        for (MemoryMessage message :
                state.recentMessages()) {

            if (message.content() != null
                    && !message.content().isBlank()) {

                text.append(message.content())
                        .append("\n");
            }
        }


        return text.toString();
    }


    private double calculateScore(
            Set<String> queryTokens,
            String compactQuery,
            String document
    ) {


        Set<String> documentTokens =
                tokenize(document);

        double score = 0;


        for (String token : queryTokens) {
            if (!documentTokens.contains(token)) {
                continue;
            }


            score += token.length() >= 4
                    ? 2.0
                    : 1.0;

        }

        String compactDocument =
                compact(document);


        if (compactQuery.length() >= 2
                && compactDocument.contains(
                compactQuery
        )) {
            score += 4.0;

        }


        return score;
    }


    private String promptText(
            MemoryConversationState state
    ) {

        List<String> parts =
                new ArrayList<>();


        if (!state.summary().isBlank()) {
            parts.add(
                    "摘要：" + state.summary()
            );
        }


        for (MemoryMessage message :
                state.recentMessages()) {


            if (message.content() == null
                    || message.content().isBlank()) {
                continue;
            }


            String role =
                    message.role() == MemoryRole.USER
                            ? "用户"
                            : "助手";


            parts.add(
                    role
                            + "："
                            + message.content().strip()
            );
        }


        String body =
                String.join(
                        "；",
                        parts
                );


        body = limitItemLength(body);


        return "历史会话["
                + state.conversationId()
                + "]："
                + body;
    }


    private Set<String> tokenize(
            String text
    ) {
        Set<String> tokens =
                new LinkedHashSet<>();

        if (text == null || text.isBlank()) {
            return tokens;
        }

        String normalized =
                text.toLowerCase(Locale.ROOT);


        Matcher alphanumericMatcher =
                ALPHANUMERIC_PATTERN.matcher(
                        normalized
                );


        while (alphanumericMatcher.find()) {
            tokens.add(
                    alphanumericMatcher.group()
            );
        }


        Matcher chineseMatcher =
                CHINESE_PATTERN.matcher(
                        normalized
                );


        while (chineseMatcher.find()) {
            String chineseText =
                    chineseMatcher.group();


            int maximumSize =
                    Math.min(
                            4,
                            chineseText.length()
                    );


            for (int size = 2;
                 size <= maximumSize;
                 size++) {


                for (int start = 0;
                     start + size
                             <= chineseText.length();
                     start++) {


                    tokens.add(
                            chineseText.substring(
                                    start,
                                    start + size
                            )
                    );
                }

            }

        }


        return tokens;
    }


    private String compact(
            String text
    ) {
        if (text == null) {
            return "";
        }

        return text
                .toLowerCase(Locale.ROOT)
                .replaceAll(
                        "[^a-z0-9\\u4e00-\\u9fff]+",
                        ""
                );
    }


    private String limitItemLength(
            String text
    ) {
        if (text.length() <= maxItemChars) {
            return text;
        }

        if (maxItemChars == 1) {
            return text.substring(
                    text.length() - 1
            );
        }

        return "…"
                + text.substring(
                text.length()
                        - maxItemChars
                        + 1
        );
    }


    private record ScoredHistory(
            String text,
            double score,
            Instant updatedAt
    ) {
    }
}
