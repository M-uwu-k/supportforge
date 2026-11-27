package com.supportforge.retrieval;

import java.util.UUID;

/** Qdrant RRF融合后的一个知识分块；score是融合排名分，不是回答正确率。 */
public record HybridSearchHit(
        UUID pointId,
        UUID documentId,
        UUID documentVersionId,
        String externalDocumentId,
        int chunkIndex,
        String content,
        String contentSha256,
        String category,
        String indexVersion,
        double score
) {

    public HybridSearchHit {
        if (pointId == null || documentId == null || documentVersionId == null) {
            throw new IllegalArgumentException("混合召回结果的UUID不能为空");
        }
        requireText(externalDocumentId, "外部文档ID");
        requireText(content, "知识正文");
        requireText(contentSha256, "正文哈希");
        requireText(category, "知识分类");
        requireText(indexVersion, "索引版本");
        if (chunkIndex < 0 || !Double.isFinite(score) || score < 0.0) {
            throw new IllegalArgumentException("混合召回结果序号或分数非法");
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
    }
}
