package com.supportforge.memory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 对持久化情景摘要进行确定性检索；后续可在不改变输出契约的情况下替换为向量召回。 */
@Component
public class EpisodicMemoryRetriever {

    private final int maximumResults;
    private final double minimumScore;

    public EpisodicMemoryRetriever(
            @Value("${supportforge.memory.episodes.max-results:3}") int maximumResults,
            @Value("${supportforge.memory.episodes.minimum-score:0.15}") double minimumScore
    ) {
        if (maximumResults < 1 || !Double.isFinite(minimumScore)
                || minimumScore < 0 || minimumScore > 1) {
            throw new IllegalArgumentException("情景记忆检索配置不合法");
        }
        this.maximumResults = maximumResults;
        this.minimumScore = minimumScore;
    }

    public List<String> retrieve(
            List<MemoryEpisode> episodes, String currentConversationId, String query
    ) {
        if (episodes == null || episodes.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }
        return episodes.stream()
                .filter(episode -> !episode.conversationId().equals(currentConversationId))
                .map(episode -> new ScoredEpisode(episode, score(queryTerms, episode)))
                .filter(scored -> scored.score() >= minimumScore)
                .sorted(Comparator.comparingDouble(ScoredEpisode::score).reversed()
                        .thenComparing(scored -> scored.episode().createdAt(), Comparator.reverseOrder()))
                .limit(maximumResults)
                .map(scored -> "情景记忆[" + scored.episode().conversationId()
                        + "/v" + scored.episode().summaryVersion() + "]："
                        + scored.episode().content())
                .toList();
    }

    private double score(Set<String> queryTerms, MemoryEpisode episode) {
        Set<String> contentTerms = terms(episode.content());
        long overlap = queryTerms.stream().filter(contentTerms::contains).count();
        if (overlap == 0) {
            return 0;
        }
        double coverage = overlap / (double) queryTerms.size();
        return Math.min(1.0, coverage * 0.8 + episode.importance() * 0.2);
    }

    private Set<String> terms(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        Set<String> terms = new LinkedHashSet<>();
        for (String token : normalized.split("[^a-z0-9_-]+")) {
            if (token.length() >= 2) {
                terms.add(token);
            }
        }
        for (int i = 0; i + 1 < normalized.length(); i++) {
            char first = normalized.charAt(i);
            char second = normalized.charAt(i + 1);
            if (isChinese(first) && isChinese(second)) {
                terms.add(normalized.substring(i, i + 2));
            }
        }
        return terms;
    }

    private boolean isChinese(char value) {
        return value >= '\u4e00' && value <= '\u9fff';
    }

    private record ScoredEpisode(MemoryEpisode episode, double score) { }
}
