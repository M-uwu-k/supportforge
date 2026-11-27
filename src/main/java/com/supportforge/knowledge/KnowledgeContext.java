package com.supportforge.knowledge;

import java.util.List;

public record KnowledgeContext(
        String promptText,
        List<KnowledgeSource> sources
) {

    public KnowledgeContext {
        promptText =
                promptText == null ? "" : promptText;

        sources = sources == null
                ? List.of()
                : List.copyOf(sources);
    }

    public boolean hasKnowledge() {
        return !sources.isEmpty();
    }
}