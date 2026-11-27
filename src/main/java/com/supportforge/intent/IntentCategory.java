package com.supportforge.intent;


public enum IntentCategory {

    QUERY("query"),
    COMPLAINT("complaint"),
    REQUEST("request"),
    GREETING("greeting"),
    ESCALATION("escalation"),
    TECHNICAL("technical"),
    BILLING("billing"),
    ACCOUNT("account"),
    FEEDBACK("feedback"),

    ORDER_STATUS("order_status"),
    LOGISTICS("logistics"),
    REFUND("refund"),
    INVOICE("invoice"),
    PAYMENT_ISSUE("payment_issue"),
    ACCOUNT_SECURITY("account_security"),
    TECHNICAL_LOGIN("technical_login"),
    TECHNICAL_CRASH("technical_crash"),
    HUMAN_HANDOFF("human_handoff"),

    OTHER("other");

    private final String value;

    IntentCategory(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static IntentCategory fromValue(String value) {
        for (IntentCategory category : values()) {
            if (category.value.equalsIgnoreCase(value)) {
                return category;
            }
        }
        return OTHER;
    }
}