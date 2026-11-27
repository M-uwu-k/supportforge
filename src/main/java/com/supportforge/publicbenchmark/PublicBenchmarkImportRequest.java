package com.supportforge.publicbenchmark;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 一批可重试、幂等写入的公开Benchmark语料。 */
public record PublicBenchmarkImportRequest(
        String datasetId,
        String datasetRevision,
        List<PublicBenchmarkPassage> passages
) {
    public PublicBenchmarkImportRequest {
        datasetId = required(datasetId, "dataset_id");
        datasetRevision = required(datasetRevision, "dataset_revision");
        passages = passages == null ? List.of() : List.copyOf(passages);
        if (passages.isEmpty()) {
            throw new IllegalArgumentException("导入passages不能为空");
        }
        Set<String> ids = new HashSet<>();
        for (PublicBenchmarkPassage passage : passages) {
            if (passage == null || !ids.add(passage.id())) {
                throw new IllegalArgumentException("导入批次包含null或重复passage id");
            }
        }
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value.strip();
    }
}

