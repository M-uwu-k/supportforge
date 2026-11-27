package com.supportforge.knowledge.ingestion;

import com.supportforge.config.KnowledgeIngestionProperties;
import com.supportforge.knowledge.KnowledgeCategory;
import com.supportforge.knowledge.KnowledgeDocument;
import com.supportforge.persistence.port.KnowledgeIngestionPersistencePort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeIngestionPipelineTest {

    @Test
    void cleansChunksAndPersistsVersionedResult() {
        RecordingPersistence persistence = new RecordingPersistence();
        KnowledgeIngestionPipeline pipeline = pipeline(persistence);

        List<KnowledgeChunk> chunks = pipeline.ingest(document());

        assertEquals(chunks, persistence.chunks);
        assertEquals(document(), persistence.expectedDocument);
        assertEquals("pipeline-document", persistence.documentId);
        assertEquals("clean-v7", persistence.parserVersion);
        assertEquals("chunk-v7", persistence.chunkerVersion);
        assertTrue(chunks.getFirst().content().contains("正文 内容"));
    }

    @Test
    void recordsFailureAndPreservesOriginalException() {
        RecordingPersistence persistence = new RecordingPersistence();
        persistence.failReplace = true;
        KnowledgeIngestionPipeline pipeline = pipeline(persistence);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> pipeline.ingest(document())
        );

        assertEquals("模拟分块持久化失败", exception.getMessage());
        assertEquals("pipeline-document", persistence.failedDocumentId);
        assertEquals("模拟分块持久化失败", persistence.failureReason);
        assertEquals(document(), persistence.failedDocument);
    }

    private KnowledgeIngestionPipeline pipeline(
            RecordingPersistence persistence
    ) {
        KnowledgeIngestionProperties properties =
                new KnowledgeIngestionProperties(
                        100,
                        20,
                        10,
                        "clean-v7",
                        "chunk-v7"
                );
        return new KnowledgeIngestionPipeline(
                new KnowledgeContentCleaner(),
                new StableKnowledgeChunker(properties),
                persistence,
                properties
        );
    }

    private KnowledgeDocument document() {
        return new KnowledgeDocument(
                "pipeline-document",
                "流水线文档",
                KnowledgeCategory.TECHNICAL,
                "正文\t  内容。\n\n第二段用于分块。",
                List.of("流水线")
        );
    }

    private static final class RecordingPersistence
            implements KnowledgeIngestionPersistencePort {
        private String documentId;
        private String parserVersion;
        private String chunkerVersion;
        private List<KnowledgeChunk> chunks = List.of();
        private boolean failReplace;
        private String failedDocumentId;
        private String failureReason;
        private KnowledgeDocument expectedDocument;
        private KnowledgeDocument failedDocument;

        @Override
        public void replaceChunks(KnowledgeDocument document, String parserVersion,
                                  String chunkerVersion, List<KnowledgeChunk> chunks) {
            this.expectedDocument = document;
            replaceChunks(document.id(), parserVersion, chunkerVersion, chunks);
        }

        @Override
        public void markFailed(KnowledgeDocument document, String failureReason) {
            this.failedDocument = document;
            markFailed(document.id(), failureReason);
        }

        @Override
        public void replaceChunks(
                String documentId,
                String parserVersion,
                String chunkerVersion,
                List<KnowledgeChunk> chunks
        ) {
            if (failReplace) {
                throw new IllegalStateException("模拟分块持久化失败");
            }
            this.documentId = documentId;
            this.parserVersion = parserVersion;
            this.chunkerVersion = chunkerVersion;
            this.chunks = List.copyOf(chunks);
        }

        @Override
        public void markFailed(
                String documentId,
                String failureReason
        ) {
            this.failedDocumentId = documentId;
            this.failureReason = failureReason;
        }
    }
}
