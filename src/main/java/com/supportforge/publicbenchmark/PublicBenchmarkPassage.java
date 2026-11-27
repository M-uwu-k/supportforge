package com.supportforge.publicbenchmark;

/** 公开数据集的原始passage；一个passage固定对应一个Qdrant Point。 */
public record PublicBenchmarkPassage(String id, String text) {
    public PublicBenchmarkPassage {
        id = required(id, "passage id");
        text = required(text, "passage text");
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value.strip();
    }
}

