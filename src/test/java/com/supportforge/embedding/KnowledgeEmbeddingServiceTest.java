package com.supportforge.embedding;

import com.supportforge.config.EmbeddingProperties;
import com.supportforge.knowledge.ingestion.KnowledgeChunk;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeEmbeddingServiceTest {

    @Test
    void mapsChunksToModelIdentifiedVectorsInOrder() {
        EmbeddingProperties properties = new EmbeddingProperties(
                "mock", "Qwen/Qwen3-Embedding-0.6B", "unit-v1",
                "http://localhost", "", 16, 10,
                Duration.ofSeconds(1), true, "instruction"
        );
        KnowledgeEmbeddingService service = new KnowledgeEmbeddingService(
                new MockQwen3EmbeddingProvider(properties)
        );

        List<EmbeddedKnowledgeChunk> result = service.embed(
                List.of(
                        chunk(0, "退款规则", 0, 4),
                        chunk(1, "登录规则", 4, 8)
                )
        );

        assertEquals(2, result.size());
        assertEquals(0, result.get(0).chunk().index());
        assertEquals(1, result.get(1).chunk().index());
        assertEquals(16, result.getFirst().denseVector().size());
        assertEquals("unit-v1", result.getFirst().modelVersion());
    }

    private KnowledgeChunk chunk(
            int index,
            String content,
            int start,
            int end
    ) {
        return new KnowledgeChunk(
                index,
                content,
                (index == 0 ? "a" : "b").repeat(64),
                4,
                start,
                end
        );
    }
}
