package com.supportforge.config;

import com.supportforge.indexing.KnowledgeIndexPermanentException;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;
import org.apache.kafka.clients.admin.NewTopic;

import java.util.HashMap;
import java.util.Map;

/** Kafka生产、消费、重试和DLT的受Feature Flag控制装配。 */
@Configuration
@EnableKafka
@EnableScheduling
@ConditionalOnProperty(
        prefix = "supportforge.features",
        name = "kafka-indexing",
        havingValue = "true"
)
public class KafkaIndexingConfiguration {

    @Bean
    KafkaAdmin supportForgeKafkaAdmin(
            KafkaIndexingProperties properties
    ) {
        return new KafkaAdmin(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                properties.bootstrapServers(),
                AdminClientConfig.CLIENT_ID_CONFIG,
                properties.clientId() + "-admin"
        ));
    }

    @Bean
    NewTopic supportForgeKnowledgeTopic(
            KafkaIndexingProperties properties
    ) {
        return TopicBuilder.name(properties.knowledgeTopic())
                .partitions(properties.partitions())
                .replicas(properties.replicationFactor())
                .config("min.insync.replicas", "1")
                .build();
    }

    @Bean
    NewTopic supportForgeKnowledgeDeadLetterTopic(
            KafkaIndexingProperties properties
    ) {
        return TopicBuilder.name(properties.deadLetterTopic())
                .partitions(properties.partitions())
                .replicas(properties.replicationFactor())
                .config("min.insync.replicas", "1")
                .build();
    }

    @Bean
    DefaultKafkaProducerFactory<String, String>
    supportForgeKafkaProducerFactory(
            KafkaIndexingProperties properties
    ) {
        Map<String, Object> config = new HashMap<>();
        config.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                properties.bootstrapServers()
        );
        config.put(ProducerConfig.CLIENT_ID_CONFIG,
                properties.clientId() + "-producer");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        int deliveryTimeout = Math.toIntExact(
                properties.sendTimeout().toMillis()
        );

        config.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        config.put(
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                Math.max(1, deliveryTimeout - 1)
        );
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, deliveryTimeout);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    KafkaTemplate<String, String> supportForgeKafkaTemplate(
            DefaultKafkaProducerFactory<String, String>
                    supportForgeKafkaProducerFactory
    ) {
        return new KafkaTemplate<>(supportForgeKafkaProducerFactory);
    }

    @Bean
    DefaultKafkaConsumerFactory<String, String>
    supportForgeKafkaConsumerFactory(
            KafkaIndexingProperties properties
    ) {
        Map<String, Object> config = new HashMap<>();
        config.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                properties.bootstrapServers()
        );
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.consumerGroup());
        config.put(ConsumerConfig.CLIENT_ID_CONFIG,
                properties.clientId() + "-consumer");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    DefaultErrorHandler supportForgeKafkaErrorHandler(
            KafkaTemplate<String, String> supportForgeKafkaTemplate,
            KafkaIndexingProperties properties
    ) {
        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(
                        supportForgeKafkaTemplate,
                        (record, exception) -> new TopicPartition(
                                properties.deadLetterTopic(),
                                record.partition()
                        )
                );
        DefaultErrorHandler handler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(
                        properties.consumerRetryDelay().toMillis(),
                        properties.consumerMaximumAttempts() - 1L
                )
        );
        handler.addNotRetryableExceptions(
                KnowledgeIndexPermanentException.class,
                DeserializationException.class
        );
        handler.setCommitRecovered(true);
        return handler;
    }

    @Bean("supportForgeKafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<String, String>
    supportForgeKafkaListenerContainerFactory(
            DefaultKafkaConsumerFactory<String, String>
                    supportForgeKafkaConsumerFactory,
            DefaultErrorHandler supportForgeKafkaErrorHandler,
            KafkaIndexingProperties properties
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(supportForgeKafkaConsumerFactory);
        factory.setCommonErrorHandler(supportForgeKafkaErrorHandler);
        factory.getContainerProperties().setAckMode(
                ContainerProperties.AckMode.RECORD
        );
        factory.setConcurrency(Math.min(3, properties.partitions()));
        return factory;
    }
}
