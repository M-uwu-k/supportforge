package com.supportforge.knowledge.ingestion;

import com.supportforge.config.KnowledgeIngestionProperties;
import com.supportforge.knowledge.KnowledgeDocument;
import com.supportforge.persistence.port.KnowledgeIngestionPersistencePort;
import org.springframework.stereotype.Service;

import java.util.List;

/** 同步的第7步解析/清洗/分块流水线；第10、11步再迁移为Outbox和Worker驱动。 */
@Service
public class KnowledgeIngestionPipeline {

    private final KnowledgeContentCleaner cleaner;
    private final StableKnowledgeChunker chunker;
    private final KnowledgeIngestionPersistencePort persistence;
    private final String parserVersion;
    private final String chunkerVersion;

    public KnowledgeIngestionPipeline(
            KnowledgeContentCleaner cleaner,
            StableKnowledgeChunker chunker,
            KnowledgeIngestionPersistencePort persistence,
            KnowledgeIngestionProperties properties
    ) {
        this.cleaner = cleaner;
        this.chunker = chunker;
        this.persistence = persistence;
        this.parserVersion = properties.parserVersion();
        this.chunkerVersion = properties.chunkerVersion();
    }

    public List<KnowledgeChunk> ingest(KnowledgeDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("待处理知识文档不能为空");
        }
        try {
            String cleaned = cleaner.clean(document.content());
            List<KnowledgeChunk> chunks = chunker.chunk(cleaned);
            persistence.replaceChunks(
                    document,
                    parserVersion,
                    chunkerVersion,
                    chunks
            );
            return chunks;
        } catch (RuntimeException exception) {
            try {
                persistence.markFailed(
                        document,
                        safeFailureReason(exception)
                );
            } catch (RuntimeException markFailure) {
                exception.addSuppressed(markFailure);
            }
            throw exception;
        }
    }

    private static String safeFailureReason(RuntimeException exception) {
        String message = exception.getMessage();
        String result = message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message.strip();
        return result.length() <= 2000
                ? result
                : result.substring(0, 2000);
    }
}
