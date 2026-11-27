package com.supportforge.publicbenchmark;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

/** 与生产知识Collection完全隔离的公开检索评测配置。 */
@ConfigurationProperties(prefix = "supportforge.public-benchmark")
public record PublicBenchmarkProperties(
        String datasetId,
        String datasetRevision,
        String collection,
        String indexVersion,
        UUID tenantId,
        int candidateLimit,
        int finalLimit,
        int rrfK,
        int maximumImportBatch,
        int maximumQueries
) {
    public PublicBenchmarkProperties {
        datasetId = normalize(datasetId, "c-mteb-ecom-retrieval");
        datasetRevision = normalize(
                datasetRevision,
                "687de13dc7294d6fd9be10c6945f9e8fec8166b9+39c90699b034ec22ac45b3abf5b0bbb5ffd421f9"
        );
        collection = normalize(collection, "supportforge_benchmark_ecom_qwen37_v1");
        indexVersion = normalize(indexVersion, "ecom-qwen37-v1");
        tenantId = tenantId == null
                ? UUID.fromString("00000000-0000-0000-0000-00000000ec01")
                : tenantId;
        candidateLimit = candidateLimit == 0 ? 100 : candidateLimit;
        finalLimit = finalLimit == 0 ? 10 : finalLimit;
        rrfK = rrfK == 0 ? 60 : rrfK;
        maximumImportBatch = maximumImportBatch == 0 ? 256 : maximumImportBatch;
        maximumQueries = maximumQueries == 0 ? 5_000 : maximumQueries;
        if (candidateLimit < 1 || candidateLimit > 1_000
                || finalLimit < 1 || finalLimit > candidateLimit) {
            throw new IllegalArgumentException("公共Benchmark候选数或最终返回数非法");
        }
        if (rrfK < 1 || rrfK > 10_000) {
            throw new IllegalArgumentException("公共Benchmark RRF常数非法");
        }
        if (maximumImportBatch < 1 || maximumImportBatch > 1_000) {
            throw new IllegalArgumentException("公共Benchmark导入批大小必须在1到1000之间");
        }
        if (maximumQueries < 1 || maximumQueries > 100_000) {
            throw new IllegalArgumentException("公共Benchmark最大查询数非法");
        }
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}

