package com.supportforge.api.dto;

import java.util.List;
import java.util.Map;


import com.supportforge.knowledge.KnowledgeSource;


public record ChatResponse(


        String conversationId,


        String responseId,


        String response,


        String intent,


        String intentGroup,


        String agentType,


        List<String> agentTypes,


        String primaryAgent,


        List<String> supportingAgents,


        String routingReason,


        double routingConfidence,


        boolean escalated,


        double latencyMs,


        boolean knowledgeUsed,


        List<KnowledgeSource> knowledgeSources,


        boolean verified,


        boolean grounded,


        List<String> verificationIssues,


        Map<String, List<String>> entities,


        double intentConfidence,


        Map<String, Double> intentSourceScores,


        java.util.UUID retrievalTraceId,
        String groundingStatus,
        List<com.supportforge.grounding.GroundingEvidence> evidence
) {


}
