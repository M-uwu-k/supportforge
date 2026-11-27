package com.supportforge.knowledge.ingestion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KnowledgeContentCleanerTest {

    @Test
    void normalizesBomControlsWhitespaceAndBlankLines() {
        String result = new KnowledgeContentCleaner().clean(
                "\uFEFF标题\r\n\r\n\r\n正文\t  内容\u0000\r尾行  "
        );

        assertEquals("标题\n\n正文 内容\n尾行", result);
    }

    @Test
    void rejectsBlankContent() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new KnowledgeContentCleaner().clean(" \n\t ")
        );
    }
}
