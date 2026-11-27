package com.supportforge.publicbenchmark;

/** 独立Collection的可复现身份和当前Point数量。 */
public record PublicBenchmarkStatus(
        String datasetId,
        String datasetRevision,
        String collection,
        String indexVersion,
        long indexedPoints,
        String embeddingModel,
        String embeddingVersion,
        int dimensions
) { }

