package com.supportforge.benchmark;

/** 固化模型和索引版本，防止不同配置的结果被错误地横向比较。 */
public record RetrievalBenchmarkEnvironment(
        String qdrantProvider,
        String qdrantCollection,
        String indexVersion,
        String embeddingProvider,
        String embeddingModel,
        String embeddingVersion,
        String rerankerProvider,
        String rerankerModel,
        String rerankerVersion,
        boolean realProviders
) { }

