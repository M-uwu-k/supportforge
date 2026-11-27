package com.supportforge.vector;

/** 应用期望且已验证的Qdrant Collection契约。 */
public record QdrantCollectionSpec(
        String collection,
        String denseVectorName,
        int dimensions,
        String distance,
        String indexVersion
) {

    public QdrantCollectionSpec {
        if (collection == null || collection.isBlank()
                || denseVectorName == null || denseVectorName.isBlank()
                || indexVersion == null || indexVersion.isBlank()) {
            throw new IllegalArgumentException("Qdrant Collection契约字段不能为空");
        }
        if (dimensions < 1) {
            throw new IllegalArgumentException("Qdrant向量维度必须为正数");
        }
    }
}
