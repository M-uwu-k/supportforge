package com.supportforge.persistence.port;

import com.supportforge.knowledge.ingestion.KnowledgeChunk;
import com.supportforge.knowledge.KnowledgeDocument;

import java.util.List;

/** 清洗/分块结果写入知识版本事实源的端口。 */
public interface KnowledgeIngestionPersistencePort {

    /** 业务流水线携带保存时的完整文档，数据库实现据指纹定位对应版本，避免并发更新串版。 */
    default void replaceChunks(KnowledgeDocument document, String parserVersion,
                               String chunkerVersion, List<KnowledgeChunk> chunks) {
        replaceChunks(document.id(), parserVersion, chunkerVersion, chunks);
    }

    default void markFailed(KnowledgeDocument document, String failureReason) {
        markFailed(document.id(), failureReason);
    }

    void replaceChunks(
            String documentId,
            String parserVersion,
            String chunkerVersion,
            List<KnowledgeChunk> chunks
    );

    void markFailed(
            String documentId,
            String failureReason
    );
}
