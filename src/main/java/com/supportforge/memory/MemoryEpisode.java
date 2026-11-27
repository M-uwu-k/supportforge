package com.supportforge.memory;

import java.time.Instant;

/** 一次摘要压缩形成的可检索情景记忆。 */
public record MemoryEpisode(
        String conversationId,
        int summaryVersion,
        String content,
        double importance,
        Instant createdAt
) {
    public MemoryEpisode {
        conversationId = conversationId == null ? "" : conversationId.strip();
        content = content == null ? "" : content.strip();
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        if (summaryVersion < 1 || importance < 0 || importance > 1) {
            throw new IllegalArgumentException("情景记忆版本或重要度不合法");
        }
    }
}
