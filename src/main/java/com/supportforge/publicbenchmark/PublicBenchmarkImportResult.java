package com.supportforge.publicbenchmark;

/** 一批语料导入结果；脚本只在成功响应后推进断点。 */
public record PublicBenchmarkImportResult(
        int received,
        int upserted,
        String firstPassageId,
        String lastPassageId,
        String collection,
        String indexVersion,
        long indexedPoints
) { }

