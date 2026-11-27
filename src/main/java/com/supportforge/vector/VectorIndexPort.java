package com.supportforge.vector;

import java.util.List;

/** 向量索引输出Port；业务服务不依赖Qdrant HTTP协议。 */
public interface VectorIndexPort {

    QdrantCollectionSpec ensureReady();

    void upsert(List<KnowledgeVectorPoint> points);
}
