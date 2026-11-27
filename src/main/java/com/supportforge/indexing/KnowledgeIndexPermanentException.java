package com.supportforge.indexing;

/** 不应在同一消息上重复尝试的问题，直接交给Kafka DLT。 */
public class KnowledgeIndexPermanentException extends RuntimeException {

    public KnowledgeIndexPermanentException(String message) {
        super(message);
    }

    public KnowledgeIndexPermanentException(String message, Throwable cause) {
        super(message, cause);
    }
}
