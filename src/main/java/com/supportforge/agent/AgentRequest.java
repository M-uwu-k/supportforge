package com.supportforge.agent;

import com.supportforge.intent.IntentCategory;
import com.supportforge.intent.UrgencyLevel;

import java.util.List;
import java.util.Map;

public record AgentRequest(
        String message,
        String userId,
        String conversationId,
        String context,
        Map<String, List<String>> entities,
        IntentCategory intent,
        String intentGroup,
        UrgencyLevel urgency,
        double intentConfidence,
        boolean ruleOnly
) {

    public AgentRequest(String message, String userId, String conversationId, String context,
                        Map<String, List<String>> entities, IntentCategory intent,
                        String intentGroup, UrgencyLevel urgency, double intentConfidence) {
        this(message, userId, conversationId, context, entities, intent,
                intentGroup, urgency, intentConfidence, false);
    }

    public AgentRequest rulesOnly() {
        return new AgentRequest(message, userId, conversationId, context, entities,
                intent, intentGroup, urgency, intentConfidence, true);
    }
}
