package com.supportforge.agent;

import com.supportforge.knowledge.KnowledgeSource;

import java.util.List;

public record AgentEnhancementResult(
        String content,
        List<KnowledgeSource> knowledgeSources
) {

    public AgentEnhancementResult {
        knowledgeSources =
                knowledgeSources == null
                        ? List.of()
                        : List.copyOf(
                        knowledgeSources
                );
    }

    public boolean knowledgeUsed() {
        return !knowledgeSources.isEmpty();
    }
}