package com.supportforge.vector;

import java.util.List;
import java.util.UUID;

/** 一条可重建Qdrant Point；Payload字段保持强类型，禁止业务代码任意拼Map。 */
public record KnowledgeVectorPoint(
        UUID pointId,
        UUID tenantId,
        UUID documentId,
        UUID documentVersionId,
        String externalDocumentId,
        int chunkIndex,
        String content,
        String contentSha256,
        String category,
        String indexVersion,
        String embeddingModel,
        String embeddingVersion,
        List<Float> denseVector,
        boolean active
) {

    public KnowledgeVectorPoint {
        if (pointId == null || tenantId == null || documentId == null
                || documentVersionId == null) {
            throw new IllegalArgumentException("向量Point的UUID字段不能为空");
        }
        requireText(externalDocumentId, "外部文档ID");
        requireText(content, "分块正文");
        requireText(contentSha256, "分块哈希");
        requireText(category, "知识分类");
        requireText(indexVersion, "索引版本");
        requireText(embeddingModel, "Embedding模型");
        requireText(embeddingVersion, "Embedding版本");
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("分块序号不能为负数");
        }
        if (!contentSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("分块哈希必须是小写SHA-256");
        }
        denseVector = List.copyOf(denseVector);
        if (denseVector.isEmpty()) {
            throw new IllegalArgumentException("稠密向量不能为空");
        }
        boolean nonZero = false;
        for (Float value : denseVector) {
            if (value == null || !Float.isFinite(value)) {
                throw new IllegalArgumentException("稠密向量只能包含有限数");
            }
            nonZero |= value != 0.0f;
        }
        if (!nonZero) {
            throw new IllegalArgumentException("稠密向量不能全部为0");
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
    }
}
