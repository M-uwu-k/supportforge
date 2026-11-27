package com.supportforge.agent;

import com.supportforge.knowledge.KnowledgeSource;
import com.supportforge.grounding.ChatGrounding;

import java.util.List;

public record OrchestratorResult(
        String response,
        AgentType agentType,
        List<AgentType> agentTypes,
        AgentType primaryAgent,
        List<AgentType> supportingAgents,
        String routingReason,
        double routingConfidence,
        boolean escalated,
        double latencyMs,
        List<KnowledgeSource> knowledgeSources,
        ChatGrounding grounding
) {

    public OrchestratorResult {
        knowledgeSources =
                knowledgeSources == null
                        ? List.of()
                        : List.copyOf(
                        knowledgeSources
                );
    }

    public OrchestratorResult(String response, AgentType agentType, List<AgentType> agentTypes,
                              AgentType primaryAgent, List<AgentType> supportingAgents,
                              String routingReason, double routingConfidence, boolean escalated,
                              double latencyMs, List<KnowledgeSource> knowledgeSources) {
        this(response, agentType, agentTypes, primaryAgent, supportingAgents, routingReason,
                routingConfidence, escalated, latencyMs, knowledgeSources, null);
    }
}
