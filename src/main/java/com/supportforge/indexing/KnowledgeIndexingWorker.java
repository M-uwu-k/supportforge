package com.supportforge.indexing;

import com.supportforge.config.DeploymentProperties;
import com.supportforge.config.QdrantProperties;
import com.supportforge.embedding.EmbeddedKnowledgeChunk;
import com.supportforge.embedding.KnowledgeEmbeddingService;
import com.supportforge.kafka.KnowledgeIndexMessage;
import com.supportforge.vector.KnowledgeVectorPoint;
import com.supportforge.vector.KnowledgeVectorPointFactory;
import com.supportforge.vector.VectorIndexPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Kafka消息驱动的完整Embedding -> Qdrant -> PostgreSQL完成Worker。 */
@Service
@ConditionalOnProperty(
        prefix = "supportforge.features",
        name = "kafka-indexing",
        havingValue = "true"
)
public class KnowledgeIndexingWorker {

    private final KnowledgeIndexJobPort jobs;
    private final KnowledgeEmbeddingService embeddings;
    private final KnowledgeVectorPointFactory pointFactory;
    private final VectorIndexPort vectorIndex;
    private final QdrantProperties qdrant;
    private final DeploymentProperties deployment;

    public KnowledgeIndexingWorker(
            KnowledgeIndexJobPort jobs,
            KnowledgeEmbeddingService embeddings,
            KnowledgeVectorPointFactory pointFactory,
            VectorIndexPort vectorIndex,
            QdrantProperties qdrant,
            DeploymentProperties deployment
    ) {
        this.jobs = jobs;
        this.embeddings = embeddings;
        this.pointFactory = pointFactory;
        this.vectorIndex = vectorIndex;
        this.qdrant = qdrant;
        this.deployment = deployment;
    }

    public void process(
            KnowledgeIndexMessage message,
            String topic,
            int partition,
            long offset
    ) {
        String owner = deployment.instanceId() + ":index-worker:"
                + Thread.currentThread().threadId();
        KnowledgeJobClaim claim = jobs.claim(
                message.eventId(),
                message.tenantId(),
                message.documentVersionId(),
                message.indexVersion(),
                owner,
                topic,
                partition,
                offset
        );
        switch (claim.status()) {
            case ALREADY_SUCCEEDED -> {
                return;
            }
            case MISSING -> throw new KnowledgeIndexPermanentException(
                    "Kafka事件没有对应PostgreSQL索引任务"
            );
            case EXHAUSTED -> throw new KnowledgeIndexPermanentException(
                    "PostgreSQL索引任务已耗尽尝试次数"
            );
            case BUSY -> throw new IllegalStateException(
                    "PostgreSQL索引任务正由其他Worker持有"
            );
            case ACQUIRED -> execute(claim.work());
        }
    }

    private void execute(KnowledgeIndexWork work) {
        try {
            List<EmbeddedKnowledgeChunk> embedded =
                    embeddings.embed(work.chunks());
            List<KnowledgeVectorPoint> points = new ArrayList<>();
            for (EmbeddedKnowledgeChunk chunk : embedded) {
                points.add(pointFactory.create(
                        work.tenantId(),
                        work.documentId(),
                        work.documentVersionId(),
                        work.externalDocumentId(),
                        work.category(),
                        chunk
                ));
            }
            vectorIndex.upsert(points);
            jobs.markSucceeded(
                    work,
                    List.copyOf(points),
                    qdrant.denseVectorName()
            );
        } catch (RuntimeException exception) {
            try {
                jobs.markFailed(work, exception.getMessage() == null
                        ? exception.getClass().getName()
                        : exception.getMessage());
            } catch (RuntimeException statusException) {
                exception.addSuppressed(statusException);
            }
            throw exception;
        }
    }
}
