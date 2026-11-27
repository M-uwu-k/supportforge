package com.supportforge.reranker;

import com.supportforge.retrieval.HybridSearchHit;

import java.util.UUID;

/** 精排后的API结果，同时保留第一阶段分数以便解释和审计。 */
public record RerankedSearchHit(
        UUID pointId,
        UUID documentId,
        UUID documentVersionId,
        String externalDocumentId,
        int chunkIndex,
        String content,
        String contentSha256,
        String category,
        String indexVersion,
        double retrievalScore,
        double rerankScore,
        int finalRank,
        String rerankerModel,
        String rerankerVersion,
        boolean reranked
) {
    static RerankedSearchHit from(
            HybridSearchHit hit,
            double rerankScore,
            int rank,
            String model,
            String version,
            boolean reranked
    ) {
        return new RerankedSearchHit(
                hit.pointId(), hit.documentId(), hit.documentVersionId(),
                hit.externalDocumentId(), hit.chunkIndex(), hit.content(),
                hit.contentSha256(), hit.category(), hit.indexVersion(),
                hit.score(), rerankScore, rank, model, version, reranked
        );
    }
}
