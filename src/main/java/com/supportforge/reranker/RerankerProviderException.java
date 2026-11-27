package com.supportforge.reranker;

public class RerankerProviderException extends RuntimeException {
    public RerankerProviderException(String message) {
        super(message);
    }

    public RerankerProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
