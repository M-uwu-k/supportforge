package com.supportforge.publicbenchmark;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 一次公共数据集运行请求；K和Candidate K与生产配置分离。 */
public record PublicBenchmarkRunRequest(
        String datasetId,
        String datasetRevision,
        Integer k,
        Integer candidateK,
        Boolean requireRealProviders,
        List<PublicBenchmarkQuery> queries
) {
    public PublicBenchmarkRunRequest {
        datasetId = required(datasetId, "dataset_id");
        datasetRevision = required(datasetRevision, "dataset_revision");
        k = k == null || k == 0 ? 10 : k;
        candidateK = candidateK == null || candidateK == 0 ? 100 : candidateK;
        requireRealProviders = requireRealProviders == null
                ? Boolean.TRUE : requireRealProviders;
        queries = queries == null ? List.of() : List.copyOf(queries);
        if (k < 1 || candidateK < k || candidateK > 1_000) {
            throw new IllegalArgumentException("公共Benchmark的K或Candidate K非法");
        }
        if (queries.isEmpty()) {
            throw new IllegalArgumentException("公共Benchmark查询不能为空");
        }
        Set<String> ids = new HashSet<>();
        for (PublicBenchmarkQuery query : queries) {
            if (query == null || !ids.add(query.id())) {
                throw new IllegalArgumentException("公共Benchmark含null或重复query id");
            }
        }
    }

    public boolean requiresRealProviders() {
        return Boolean.TRUE.equals(requireRealProviders);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value.strip();
    }
}

