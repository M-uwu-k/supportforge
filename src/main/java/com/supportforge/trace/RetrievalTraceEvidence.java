package com.supportforge.trace;

import java.util.UUID;

/** 写入retrieval_traces JSONB的稳定证据快照。 */
public record RetrievalTraceEvidence(
        String citationId,
        UUID pointId,
        UUID documentId,
        UUID documentVersionId,
        int chunkIndex,
        String contentSha256,
        double retrievalScore,
        double rerankScore
) {
}
