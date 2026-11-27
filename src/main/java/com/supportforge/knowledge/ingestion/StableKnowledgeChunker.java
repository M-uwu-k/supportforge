package com.supportforge.knowledge.ingestion;

import com.supportforge.config.KnowledgeIngestionProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 优先在段落、换行和句末切分的确定性字符分块器；相同正文和配置始终产生相同分块。
 */
@Component
public class StableKnowledgeChunker {

    private final int maximumChars;
    private final int minimumChars;
    private final int overlapChars;

    @Autowired
    public StableKnowledgeChunker(
            KnowledgeIngestionProperties properties
    ) {
        this.maximumChars = properties.maximumChunkChars();
        this.minimumChars = properties.minimumChunkChars();
        this.overlapChars = properties.overlapChars();
    }

    StableKnowledgeChunker(
            int maximumChars,
            int minimumChars,
            int overlapChars
    ) {
        this(new KnowledgeIngestionProperties(
                maximumChars,
                minimumChars,
                overlapChars,
                "test-parser",
                "test-chunker"
        ));
    }

    public List<KnowledgeChunk> chunk(String cleanedContent) {
        if (cleanedContent == null || cleanedContent.isBlank()) {
            throw new IllegalArgumentException("清洗后正文不能为空");
        }
        List<KnowledgeChunk> chunks = new ArrayList<>();
        int start = 0;
        while (start < cleanedContent.length()) {
            int hardEnd = Math.min(
                    cleanedContent.length(),
                    start + maximumChars
            );
            int end = hardEnd == cleanedContent.length()
                    ? hardEnd
                    : preferredBoundary(
                            cleanedContent,
                            start + minimumChars,
                            hardEnd
                    );
            if (end <= start) {
                end = hardEnd;
            }

            String content = cleanedContent.substring(start, end).strip();
            if (!content.isEmpty()) {
                chunks.add(new KnowledgeChunk(
                        chunks.size(),
                        content,
                        sha256(content),
                        estimateTokens(content),
                        start,
                        end
                ));
            }
            if (end >= cleanedContent.length()) {
                break;
            }
            int nextStart = Math.max(start + 1, end - overlapChars);
            while (nextStart < end
                    && Character.isWhitespace(
                    cleanedContent.charAt(nextStart)
            )) {
                nextStart++;
            }
            start = nextStart;
        }
        return List.copyOf(chunks);
    }

    private static int preferredBoundary(
            String content,
            int minimumEnd,
            int hardEnd
    ) {
        int safeMinimum = Math.min(minimumEnd, hardEnd);
        for (String boundary : List.of("\n\n", "\n")) {
            int position = content.lastIndexOf(boundary, hardEnd - 1);
            if (position >= safeMinimum) {
                return position + boundary.length();
            }
        }
        for (int index = hardEnd - 1; index >= safeMinimum; index--) {
            char value = content.charAt(index);
            if ("。！？.!?；;".indexOf(value) >= 0) {
                return index + 1;
            }
        }
        return hardEnd;
    }

    private static int estimateTokens(String content) {
        int tokens = 0;
        int asciiRun = 0;
        for (int offset = 0; offset < content.length();) {
            int codePoint = content.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                tokens += (asciiRun + 3) / 4;
                asciiRun = 0;
            } else if (codePoint <= 0x7f
                    && Character.isLetterOrDigit(codePoint)) {
                asciiRun++;
            } else {
                tokens += (asciiRun + 3) / 4;
                asciiRun = 0;
                tokens++;
            }
        }
        tokens += (asciiRun + 3) / 4;
        return Math.max(1, tokens);
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(content.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "当前JVM不支持SHA-256",
                    exception
            );
        }
    }
}
