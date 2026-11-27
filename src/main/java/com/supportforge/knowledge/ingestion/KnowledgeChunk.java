package com.supportforge.knowledge.ingestion;

/** 清洗后文档中的一个稳定分块。 */
public record KnowledgeChunk(
        int index,
        String content,
        String checksumSha256,
        int estimatedTokens,
        int startOffset,
        int endOffset
) {

    public KnowledgeChunk {
        if (index < 0) {
            throw new IllegalArgumentException("分块序号不能为负数");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("分块正文不能为空");
        }
        if (checksumSha256 == null
                || !checksumSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("分块SHA-256格式非法");
        }
        if (estimatedTokens < 1) {
            throw new IllegalArgumentException("分块token估算必须至少为1");
        }
        if (startOffset < 0 || endOffset <= startOffset) {
            throw new IllegalArgumentException("分块字符偏移非法");
        }
    }
}
