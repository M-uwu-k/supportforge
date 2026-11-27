package com.supportforge.embedding;

import java.util.List;

/** 一个输入对应的稠密向量；List.copyOf避免调用方修改维度或元素。 */
public record EmbeddingVector(
        int index,
        List<Float> values
) {

    public EmbeddingVector {
        if (index < 0) {
            throw new IllegalArgumentException("Embedding序号不能为负数");
        }
        values = List.copyOf(values);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Embedding向量不能为空");
        }
        boolean nonZero = false;
        for (Float value : values) {
            if (value == null || !Float.isFinite(value)) {
                throw new IllegalArgumentException(
                        "Embedding向量只能包含有限数"
                );
            }
            nonZero |= value != 0.0f;
        }
        if (!nonZero) {
            throw new IllegalArgumentException(
                    "Embedding向量不能全部为0"
            );
        }
    }
}
