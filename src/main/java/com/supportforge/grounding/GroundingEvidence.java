package com.supportforge.grounding;

import java.util.UUID;

/** 通过安全与相关性门槛、允许送入LLM的证据快照。 */
public record GroundingEvidence(
        String citationId,
        UUID pointId,
        UUID documentId,
        UUID documentVersionId,
        String externalDocumentId,
        int chunkIndex,
        String content,
        String contentSha256,
        String category,
        double retrievalScore,
        double rerankScore
) {
    public GroundingEvidence {
        if (citationId == null || !citationId.matches("E[1-9][0-9]*")) {
            throw new IllegalArgumentException("证据引用编号必须是E加正整数");
        }
    }

    public GroundingEvidence(
            UUID pointId,
            UUID documentId,
            UUID documentVersionId,
            String externalDocumentId,
            int chunkIndex,
            String content,
            String contentSha256,
            String category,
            double retrievalScore,
            double rerankScore
    ) {
        this("E1", pointId, documentId, documentVersionId,
                externalDocumentId, chunkIndex, content, contentSha256,
                category, retrievalScore, rerankScore);
    }
}
