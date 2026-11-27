package com.supportforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Kafka索引事件、Publisher、consumer和DLT配置。 */
@ConfigurationProperties(prefix = "supportforge.kafka")
public record KafkaIndexingProperties(
        String bootstrapServers,
        String knowledgeTopic,
        String deadLetterTopic,
        String consumerGroup,
        String clientId,
        int partitions,
        short replicationFactor,
        Duration outboxPollDelay,
        Duration sendTimeout,
        Duration consumerRetryDelay,
        int consumerMaximumAttempts,
        Duration workerLeaseDuration
) {

    public KafkaIndexingProperties {
        bootstrapServers = normalize(bootstrapServers, "localhost:29092");
        knowledgeTopic = normalize(
                knowledgeTopic,
                "supportforge.knowledge.index.v1"
        );
        deadLetterTopic = normalize(
                deadLetterTopic,
                "supportforge.knowledge.index.v1.dlt"
        );
        consumerGroup = normalize(
                consumerGroup,
                "supportforge-knowledge-indexer-v1"
        );
        clientId = normalize(clientId, "supportforge");
        partitions = partitions == 0 ? 6 : partitions;
        replicationFactor = replicationFactor == 0 ? 1 : replicationFactor;
        outboxPollDelay = outboxPollDelay == null
                ? Duration.ofSeconds(1) : outboxPollDelay;
        sendTimeout = sendTimeout == null
                ? Duration.ofSeconds(10) : sendTimeout;
        consumerRetryDelay = consumerRetryDelay == null
                ? Duration.ofSeconds(2) : consumerRetryDelay;
        consumerMaximumAttempts = consumerMaximumAttempts == 0
                ? 5 : consumerMaximumAttempts;
        workerLeaseDuration = workerLeaseDuration == null
                ? Duration.ofMinutes(2) : workerLeaseDuration;

        if (partitions < 1 || partitions > 1_000
                || replicationFactor < 1
                || consumerMaximumAttempts < 1
                || consumerMaximumAttempts > 1_000) {
            throw new IllegalArgumentException("Kafka分区、副本或尝试次数非法");
        }
        if (outboxPollDelay.isZero() || outboxPollDelay.isNegative()
                || sendTimeout.isZero() || sendTimeout.isNegative()
                || consumerRetryDelay.isNegative()
                || workerLeaseDuration.isZero()
                || workerLeaseDuration.isNegative()) {
            throw new IllegalArgumentException("Kafka时间配置非法");
        }
        if (knowledgeTopic.equals(deadLetterTopic)) {
            throw new IllegalArgumentException("Kafka主Topic和DLT不能相同");
        }
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
