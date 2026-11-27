package com.supportforge.embedding;

import com.supportforge.config.EmbeddingProperties;

import java.util.ArrayList;
import java.util.List;

final class EmbeddingSupport {

    private EmbeddingSupport() {
    }

    static List<String> validateAndFormat(
            List<String> texts,
            EmbeddingPurpose purpose,
            EmbeddingProperties properties
    ) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("Embedding输入不能为空");
        }
        if (purpose == null) {
            throw new IllegalArgumentException("Embedding用途不能为空");
        }
        List<String> formatted = new ArrayList<>(texts.size());
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException(
                        "Embedding输入不能包含空文本"
                );
            }
            String normalized = text.strip();
            formatted.add(purpose == EmbeddingPurpose.QUERY
                    ? "Instruct: " + properties.queryInstruction()
                    + "\nQuery:" + normalized
                    : normalized);
        }
        return List.copyOf(formatted);
    }

    static List<Float> normalize(
            List<Float> values,
            boolean enabled
    ) {
        double squaredNorm = 0.0;
        for (Float value : values) {
            if (value == null || !Float.isFinite(value)) {
                throw new EmbeddingProviderException(
                        "Embedding响应包含非有限数"
                );
            }
            squaredNorm += (double) value * value;
        }
        if (squaredNorm == 0.0) {
            throw new EmbeddingProviderException(
                    "Embedding响应向量全部为0"
            );
        }
        if (!enabled) {
            return List.copyOf(values);
        }
        double norm = Math.sqrt(squaredNorm);
        List<Float> normalized = new ArrayList<>(values.size());
        for (Float value : values) {
            normalized.add((float) (value / norm));
        }
        return List.copyOf(normalized);
    }
}
