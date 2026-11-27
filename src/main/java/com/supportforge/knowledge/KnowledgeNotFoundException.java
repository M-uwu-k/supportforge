package com.supportforge.knowledge;

public class KnowledgeNotFoundException
        extends RuntimeException {

    public KnowledgeNotFoundException(String message) {
        super(message);
    }
}