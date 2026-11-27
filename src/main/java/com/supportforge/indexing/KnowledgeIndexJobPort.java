package com.supportforge.indexing;

import com.supportforge.vector.KnowledgeVectorPoint;

import java.util.List;
import java.util.UUID;

/** PostgreSQL索引任务Port。Qdrant成功后以稳定Point列表完成事实源状态。 */
public interface KnowledgeIndexJobPort {

    KnowledgeJobClaim claim(
            UUID eventId,
            UUID tenantId,
            UUID documentVersionId,
            String indexVersion,
            String owner,
            String topic,
            int partition,
            long offset
    );

    void markSucceeded(
            KnowledgeIndexWork work,
            List<KnowledgeVectorPoint> points,
            String vectorName
    );

    void markFailed(KnowledgeIndexWork work, String errorMessage);
}
