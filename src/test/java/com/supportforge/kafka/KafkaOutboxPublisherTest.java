package com.supportforge.kafka;

import com.supportforge.config.DeploymentProperties;
import com.supportforge.config.KafkaIndexingProperties;
import com.supportforge.outbox.OutboxEvent;
import com.supportforge.outbox.OutboxRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaOutboxPublisherTest {

    @Test
    void marksPublishedOnlyAfterBrokerFutureCompletes() {
        OutboxRepositoryPort outbox = mock(OutboxRepositoryPort.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        OutboxEvent event = event();
        when(outbox.claim("instance-1:outbox-publisher"))
                .thenReturn(List.of(event));
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher(outbox, template).publishAvailable();

        verify(outbox).markPublished(
                event.id(),
                "instance-1:outbox-publisher"
        );
    }

    @Test
    void recordsOutboxFailureWhenBrokerFutureFails() {
        OutboxRepositoryPort outbox = mock(OutboxRepositoryPort.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        OutboxEvent event = event();
        when(outbox.claim("instance-1:outbox-publisher"))
                .thenReturn(List.of(event));
        CompletableFuture<SendResult<String, String>> failed =
                CompletableFuture.failedFuture(
                        new IllegalStateException("broker down")
                );
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(failed);

        publisher(outbox, template).publishAvailable();

        verify(outbox).markFailed(
                org.mockito.ArgumentMatchers.eq(event.id()),
                org.mockito.ArgumentMatchers.eq("instance-1:outbox-publisher"),
                anyString()
        );
    }

    private KafkaOutboxPublisher publisher(
            OutboxRepositoryPort outbox,
            KafkaTemplate<String, String> template
    ) {
        return new KafkaOutboxPublisher(
                outbox, template, properties(), new ObjectMapper(),
                new DeploymentProperties("test", "instance-1")
        );
    }

    private KafkaIndexingProperties properties() {
        return new KafkaIndexingProperties(
                "localhost:9092", "topic", "topic.dlt", "group", "client",
                1, (short) 1, Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ZERO, 1,
                Duration.ofSeconds(30)
        );
    }

    private OutboxEvent event() {
        UUID tenant = UUID.randomUUID();
        UUID aggregate = UUID.randomUUID();
        return new OutboxEvent(
                UUID.randomUUID(), tenant, "KnowledgeVersion", aggregate,
                1, "chunks.ready.v1", 1, "topic",
                tenant + ":" + aggregate, "idem",
                Map.of(
                        "tenant_id", tenant.toString(),
                        "document_id", aggregate.toString(),
                        "document_version_id", UUID.randomUUID().toString(),
                        "external_document_id", "refund",
                        "index_version", "v1"
                ),
                Map.of("schema_version", 1), 1,
                Instant.now().plusSeconds(30), Instant.now()
        );
    }
}
