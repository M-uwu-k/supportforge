package com.supportforge.knowledge.ingestion;

import org.springframework.stereotype.Component;

/**
 * 确定性文本清洗器：统一换行、移除BOM/不可见控制字符、压缩行内空白和多余空行。
 */
@Component
public class KnowledgeContentCleaner {

    public String clean(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("待解析知识正文不能为空");
        }
        String normalized = content
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\uFEFF", "");

        StringBuilder cleaned = new StringBuilder(normalized.length());
        boolean previousHorizontalWhitespace = false;
        int consecutiveNewlines = 0;
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (codePoint == '\n') {
                trimTrailingSpace(cleaned);
                if (consecutiveNewlines < 2) {
                    cleaned.append('\n');
                }
                consecutiveNewlines++;
                previousHorizontalWhitespace = false;
                continue;
            }
            if (Character.isISOControl(codePoint)) {
                continue;
            }
            consecutiveNewlines = 0;
            if (Character.isWhitespace(codePoint)) {
                if (!previousHorizontalWhitespace
                        && !cleaned.isEmpty()
                        && cleaned.charAt(cleaned.length() - 1) != '\n') {
                    cleaned.append(' ');
                }
                previousHorizontalWhitespace = true;
                continue;
            }
            cleaned.appendCodePoint(codePoint);
            previousHorizontalWhitespace = false;
        }
        return cleaned.toString().strip();
    }

    private static void trimTrailingSpace(StringBuilder value) {
        while (!value.isEmpty()
                && value.charAt(value.length() - 1) == ' ') {
            value.deleteCharAt(value.length() - 1);
        }
    }
}
