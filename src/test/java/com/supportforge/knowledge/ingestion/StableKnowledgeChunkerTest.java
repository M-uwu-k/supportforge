package com.supportforge.knowledge.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StableKnowledgeChunkerTest {

    @Test
    void producesDeterministicBoundedChunksWithOverlap() {
        StableKnowledgeChunker chunker =
                new StableKnowledgeChunker(100, 40, 20);
        String content = ("第一段用于验证稳定分块。这里包含足够多的中文字符。\n\n"
                + "第二段继续补充内容，并确保总长度超过一个窗口。"
                + "同一输入必须得到相同的序号、偏移和校验和。\n\n"
                + "第三段作为结尾，验证最后一个分块不会丢失。")
                .repeat(2);

        List<KnowledgeChunk> first = chunker.chunk(content);
        List<KnowledgeChunk> second = chunker.chunk(content);

        assertEquals(first, second);
        assertTrue(first.size() > 1);
        for (int index = 0; index < first.size(); index++) {
            KnowledgeChunk chunk = first.get(index);
            assertEquals(index, chunk.index());
            assertTrue(chunk.content().length() <= 100);
            assertEquals(64, chunk.checksumSha256().length());
            assertTrue(chunk.estimatedTokens() > 0);
        }
        assertTrue(first.get(1).startOffset()
                < first.getFirst().endOffset());
    }

    @Test
    void keepsShortDocumentAsOneChunk() {
        List<KnowledgeChunk> chunks =
                new StableKnowledgeChunker(100, 20, 0)
                        .chunk("一篇很短但有效的知识正文。");

        assertEquals(1, chunks.size());
        assertEquals(0, chunks.getFirst().startOffset());
        assertFalse(chunks.getFirst().content().isBlank());
    }
}
