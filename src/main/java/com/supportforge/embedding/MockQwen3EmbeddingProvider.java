package com.supportforge.embedding;

import com.supportforge.config.EmbeddingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * local/test确定性稠密向量。它只模拟协议、维度和归一化，不能代表Qwen3真实语义质量。
 */
@Component
@ConditionalOnProperty(
        prefix = "supportforge.embedding",
        name = "provider",
        havingValue = "mock",
        matchIfMissing = true
)
public class MockQwen3EmbeddingProvider
        implements EmbeddingProvider {

    private final EmbeddingProperties properties;

    public MockQwen3EmbeddingProvider(
            EmbeddingProperties properties
    ) {
        this.properties = properties;
    }

    @Override
    public EmbeddingBatch embed(
            List<String> texts,
            EmbeddingPurpose purpose
    ) {
        List<String> validated = EmbeddingSupport.validateAndFormat(
                texts,
                purpose,
                properties
        );
        List<EmbeddingVector> vectors = new ArrayList<>();
        int tokens = 0;
        for (int index = 0; index < validated.size(); index++) {

            String original = texts.get(index).strip();
            vectors.add(new EmbeddingVector(
                    index,
                    vectorize(original)
            ));
            tokens += Math.max(1, original.codePointCount(
                    0,
                    original.length()
            ));
        }
        return new EmbeddingBatch(
                properties.model(),
                properties.modelVersion(),
                properties.dimensions(),
                vectors,
                tokens
        );
    }

    private List<Float> vectorize(String text) {
        float[] values = new float[properties.dimensions()];
        String normalized = text.toLowerCase(java.util.Locale.ROOT);
        int[] codePoints = normalized.codePoints().toArray();
        for (int width = 1; width <= 3; width++) {
            if (codePoints.length < width) {
                continue;
            }
            for (int start = 0;
                 start + width <= codePoints.length;
                 start++) {
                String feature = new String(
                        codePoints,
                        start,
                        width
                );
                byte[] hash = sha256(feature);
                int bucket = Math.floorMod(
                        ByteBuffer.wrap(hash, 0, 4).getInt(),
                        values.length
                );
                float sign = (hash[4] & 1) == 0 ? 1.0f : -1.0f;
                values[bucket] += sign;
            }
        }
        List<Float> result = new ArrayList<>(values.length);
        for (float value : values) {
            result.add(value);
        }
        return EmbeddingSupport.normalize(
                result,
                properties.normalize()
        );
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "当前JVM不支持SHA-256",
                    exception
            );
        }
    }
}
