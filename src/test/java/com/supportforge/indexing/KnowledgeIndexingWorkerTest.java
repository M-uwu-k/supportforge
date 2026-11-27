package com.supportforge.indexing;

import com.supportforge.config.DeploymentProperties;
import com.supportforge.config.QdrantProperties;
import com.supportforge.embedding.EmbeddedKnowledgeChunk;
import com.supportforge.embedding.KnowledgeEmbeddingService;
import com.supportforge.kafka.KnowledgeIndexMessage;
import com.supportforge.knowledge.ingestion.KnowledgeChunk;
import com.supportforge.vector.KnowledgeVectorPoint;
import com.supportforge.vector.KnowledgeVectorPointFactory;
import com.supportforge.vector.VectorIndexPort;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIndexingWorkerTest {

    @Test
    void embedsUpsertsAndCompletesAnAcquiredJob() {
        Fixture fixture = fixture();
        when(fixture.jobs.claim(any(), any(), any(), anyString(),
                anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(KnowledgeJobClaim.acquired(fixture.work));
        when(fixture.embeddings.embed(fixture.work.chunks()))
                .thenReturn(List.of(fixture.embedded));
        when(fixture.pointFactory.create(any(), any(), any(),
                anyString(), anyString(), any()))
                .thenReturn(fixture.point);

        fixture.worker.process(fixture.message, "topic", 1, 9L);

        verify(fixture.vectorIndex).upsert(List.of(fixture.point));
        verify(fixture.jobs).markSucceeded(
                fixture.work,
                List.of(fixture.point),
                "dense"
        );
    }

    @Test
    void duplicateAfterSuccessDoesNoEmbeddingOrUpsert() {
        Fixture fixture = fixture();
        when(fixture.jobs.claim(any(), any(), any(), anyString(),
                anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(KnowledgeJobClaim.of(
                        KnowledgeJobClaim.Status.ALREADY_SUCCEEDED
                ));

        fixture.worker.process(fixture.message, "topic", 1, 9L);

        verify(fixture.embeddings, never()).embed(any());
        verify(fixture.vectorIndex, never()).upsert(any());
    }

    @Test
    void marksJobFailedAndRethrowsWhenQdrantFails() {
        Fixture fixture = fixture();
        when(fixture.jobs.claim(any(), any(), any(), anyString(),
                anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(KnowledgeJobClaim.acquired(fixture.work));
        when(fixture.embeddings.embed(fixture.work.chunks()))
                .thenReturn(List.of(fixture.embedded));
        when(fixture.pointFactory.create(any(), any(), any(),
                anyString(), anyString(), any()))
                .thenReturn(fixture.point);
        org.mockito.Mockito.doThrow(new IllegalStateException("qdrant down"))
                .when(fixture.vectorIndex).upsert(any());

        assertThrows(
                IllegalStateException.class,
                () -> fixture.worker.process(fixture.message, "topic", 1, 9L)
        );

        verify(fixture.jobs).markFailed(
                fixture.work,
                "qdrant down"
        );
    }

    private Fixture fixture() {
        KnowledgeIndexJobPort jobs = mock(KnowledgeIndexJobPort.class);
        KnowledgeEmbeddingService embeddings = mock(KnowledgeEmbeddingService.class);
        KnowledgeVectorPointFactory pointFactory = mock(KnowledgeVectorPointFactory.class);
        VectorIndexPort vectorIndex = mock(VectorIndexPort.class);
        QdrantProperties qdrant = new QdrantProperties(
                "memory", "http://localhost:6333", "",
                "knowledge", "dense", "index-v1", 4, "Cosine",
                1, 1, 1, 10, Duration.ofSeconds(1)
        );
        UUID eventId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        KnowledgeChunk chunk = new KnowledgeChunk(
                0, "正文", "a".repeat(64), 2, 0, 2
        );
        KnowledgeIndexWork work = new KnowledgeIndexWork(
                UUID.randomUUID(), eventId, tenantId, documentId,
                versionId, "refund", "BILLING", "index-v1",
                "instance-1:index-worker:1", List.of(chunk)
        );
        EmbeddedKnowledgeChunk embedded = new EmbeddedKnowledgeChunk(
                chunk, List.of(1f, 0f, 0f, 0f),
                "model", "model-v1", 4
        );
        KnowledgeVectorPoint point = new KnowledgeVectorPoint(
                UUID.randomUUID(), tenantId, documentId, versionId,
                "refund", 0, "正文", "a".repeat(64), "BILLING",
                "index-v1", "model", "model-v1",
                List.of(1f, 0f, 0f, 0f), true
        );
        KnowledgeIndexMessage message = new KnowledgeIndexMessage(
                eventId, tenantId, documentId, versionId,
                "refund", "index-v1"
        );
        KnowledgeIndexingWorker worker = new KnowledgeIndexingWorker(
                jobs, embeddings, pointFactory, vectorIndex, qdrant,
                new DeploymentProperties("test", "instance-1")
        );
        return new Fixture(
                jobs, embeddings, pointFactory, vectorIndex,
                work, embedded, point, message, worker
        );
    }

    private record Fixture(
            KnowledgeIndexJobPort jobs,
            KnowledgeEmbeddingService embeddings,
            KnowledgeVectorPointFactory pointFactory,
            VectorIndexPort vectorIndex,
            KnowledgeIndexWork work,
            EmbeddedKnowledgeChunk embedded,
            KnowledgeVectorPoint point,
            KnowledgeIndexMessage message,
            KnowledgeIndexingWorker worker
    ) {
    }
}
