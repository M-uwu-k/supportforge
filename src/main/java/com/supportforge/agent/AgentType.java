package com.supportforge.agent;

public enum AgentType {

    GENERAL("general"),
    TECHNICAL("technical"),
    BILLING("billing"),
    ESCALATION("escalation");

    private final String value;

    AgentType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}