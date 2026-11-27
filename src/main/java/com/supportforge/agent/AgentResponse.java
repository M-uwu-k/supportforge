package com.supportforge.agent;

import com.supportforge.knowledge.KnowledgeSource;

import java.util.List;

public record AgentResponse(
        AgentType agentType,
        String content,
        boolean success,
        double confidence,
        double latencyMs,
        boolean escalate,
        List<KnowledgeSource> knowledgeSources
) {

    public AgentResponse {
        knowledgeSources =
                knowledgeSources == null
                        ? List.of()
                        : List.copyOf(
                        knowledgeSources
                );
    }
}