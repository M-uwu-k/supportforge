package com.supportforge.benchmark;

/** 请求正式报告，但当前模型或索引Provider仍是Mock/内存实现。 */
public class RetrievalBenchmarkEnvironmentException extends RuntimeException {
    public RetrievalBenchmarkEnvironmentException(String message) {
        super(message);
    }
}

