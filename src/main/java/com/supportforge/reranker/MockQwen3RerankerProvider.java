package com.supportforge.reranker;

import com.supportforge.config.RerankerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 无外部依赖的确定性Reranker，用于local/test，不冒充真实模型质量。 */
@Component
@ConditionalOnProperty(
        prefix = "supportforge.reranker", name = "provider",
        havingValue = "mock", matchIfMissing = true
)
public class MockQwen3RerankerProvider implements RerankerProvider {

    private final RerankerProperties properties;

    public MockQwen3RerankerProvider(RerankerProperties properties) {
        this.properties = properties;
    }

    @Override
    public RerankBatch rerank(String query, List<String> documents) {
        validate(query, documents);
        Set<Integer> queryPoints = codePoints(query);
        List<RerankScore> scores = new ArrayList<>();
        for (int index = 0; index < documents.size(); index++) {
            Set<Integer> document = codePoints(documents.get(index));
            long overlap = queryPoints.stream().filter(document::contains).count();
            double denominator = Math.sqrt(
                    Math.max(1, queryPoints.size()) * Math.max(1, document.size())
            );
            scores.add(new RerankScore(index, Math.min(1.0, overlap / denominator)));
        }
        return new RerankBatch(
                properties.model(), properties.modelVersion(), scores
        );
    }

    private static Set<Integer> codePoints(String text) {
        Set<Integer> result = new LinkedHashSet<>();
        text.strip().toLowerCase(Locale.ROOT).codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(result::add);
        return result;
    }

    static void validate(String query, List<String> documents) {
        if (query == null || query.isBlank()
                || documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("精排问题和候选文档不能为空");
        }
        if (documents.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("精排候选正文不能为空");
        }
    }
}
