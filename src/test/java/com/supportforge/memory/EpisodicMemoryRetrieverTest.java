package com.supportforge.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpisodicMemoryRetrieverTest {

    @Test
    void retrievesRelevantEpisodeAndExcludesCurrentConversation() {
        EpisodicMemoryRetriever retriever = new EpisodicMemoryRetriever(3, 0.15);
        List<MemoryEpisode> episodes = List.of(
                new MemoryEpisode("old-refund", 2, "订单退款预计三个工作日到账", 1, Instant.now()),
                new MemoryEpisode("current", 1, "当前退款会话", 1, Instant.now()),
                new MemoryEpisode("login", 1, "验证码登录失败", 1, Instant.now())
        );

        List<String> result = retriever.retrieve(episodes, "current", "退款多久到账");

        assertEquals(1, result.size());
        assertTrue(result.getFirst().startsWith("情景记忆[old-refund/v2]"));
    }

    @Test
    void rejectsInvalidScoreConfiguration() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new EpisodicMemoryRetriever(3, 1.1)
        );
    }
}
