package com.supportforge.intent;

public enum UrgencyLevel {

    LOW(1),
    MEDIUM(2),
    HIGH(3),
    CRITICAL(4);

    private final int level;

    UrgencyLevel(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }
}