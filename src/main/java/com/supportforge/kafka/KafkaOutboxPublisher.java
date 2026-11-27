package com.supportforge.kafka;

import com.supportforge.config.DeploymentProperties;
import com.supportforge.config.KafkaIndexingProperties;
import com.supportforge.outbox.OutboxEvent;
import com.supportforge.outbox.OutboxRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 轮询已提交Outbox，等待Kafka broker确认后才把事件标记PUBLISHED。 */
@Component
@ConditionalOnProperty(
        prefix = "supportforge.features",
        name = "kafka-indexing",
        havingValue = "true"
)
public class KafkaOutboxPublisher {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(KafkaOutboxPublisher.class);

    private final OutboxRepositoryPort outbox;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaIndexingProperties properties;
    private final ObjectMapper objectMapper;
    private final String owner;

    public KafkaOutboxPublisher(
            OutboxRepositoryPort outbox,
            KafkaTemplate<String, String> kafkaTemplate,
            KafkaIndexingProperties properties,
            ObjectMapper objectMapper,
            DeploymentProperties deployment
    ) {
        this.outbox = outbox;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.owner = deployment.instanceId() + ":outbox-publisher";
    }

    @Scheduled(
            fixedDelayString = "${supportforge.kafka.outbox-poll-delay:1s}"
    )
    public void publishAvailable() {
        List<OutboxEvent> events = outbox.claim(owner);
        for (OutboxEvent event : events) {
            publishOne(event);
        }
    }

    private void publishOne(OutboxEvent event) {
        try {
            String body = objectMapper.writeValueAsString(envelope(event));
            kafkaTemplate.send(
                            event.topic(),
                            event.partitionKey(),
                            body
                    )
                    .get(
                            properties.sendTimeout().toMillis(),
                            TimeUnit.MILLISECONDS
                    );
            outbox.markPublished(event.id(), owner);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            try {
                outbox.markFailed(
                        event.id(),
                        owner,
                        exception.getMessage() == null
                                ? exception.getClass().getName()
                                : exception.getMessage()
                );
            } catch (RuntimeException statusException) {
                exception.addSuppressed(statusException);
                LOGGER.error(
                        "Kafka发送失败且Outbox状态更新失败，eventId={}",
                        event.id(),
                        exception
                );
            }
        }
    }

    private Map<String, Object> envelope(OutboxEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event_id", event.id().toString());
        body.put("tenant_id", event.tenantId().toString());
        body.put("aggregate_type", event.aggregateType());
        body.put("aggregate_id", event.aggregateId().toString());
        body.put("aggregate_version", event.aggregateVersion());
        body.put("event_type", event.eventType());
        body.put("event_version", event.eventVersion());
        body.put("idempotency_key", event.idempotencyKey());
        body.put("occurred_at", event.createdAt().toString());
        body.put("payload", event.payload());
        body.put("headers", event.headers());
        return body;
    }
}
