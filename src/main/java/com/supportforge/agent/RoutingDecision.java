package com.supportforge.agent;

import java.util.ArrayList;
import java.util.List;

public record RoutingDecision(
        AgentType primaryAgent,
        List<AgentType> supportingAgents,
        String reason,
        double confidence
) {

    public RoutingDecision {
        supportingAgents = List.copyOf(supportingAgents);
    }

    public List<AgentType> agentTypes() {
        List<AgentType> result = new ArrayList<>();
        result.add(primaryAgent);
        result.addAll(supportingAgents);
        return List.copyOf(result);
    }

    public boolean multiAgent() {
        return !supportingAgents.isEmpty();
    }
}