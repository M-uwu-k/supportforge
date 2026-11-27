package com.supportforge.publicbenchmark;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 标准qrels查询；相关度分数支持未来的T2Ranking四级标注。 */
public record PublicBenchmarkQuery(
        String id,
        String text,
        Map<String, Integer> relevanceJudgments
) {
    public PublicBenchmarkQuery {
        id = required(id, "query id");
        text = required(text, "query text");
        if (relevanceJudgments == null || relevanceJudgments.isEmpty()) {
            throw new IllegalArgumentException("query至少需要一个qrels标注：" + id);
        }
        LinkedHashMap<String, Integer> normalized = new LinkedHashMap<>();
        relevanceJudgments.forEach((documentId, score) -> {
            String normalizedId = required(documentId, "qrels document id");
            if (score == null || score < 1 || score > 100) {
                throw new IllegalArgumentException("qrels相关度必须在1到100之间");
            }
            normalized.put(normalizedId, score);
        });
        relevanceJudgments = Collections.unmodifiableMap(normalized);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value.strip();
    }
}

