package com.supportforge.kafka;

import com.supportforge.indexing.KnowledgeIndexingWorker;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 主Topic Listener；抛出的异常由DefaultErrorHandler重试并最终发布DLT。 */
@Component
@ConditionalOnProperty(
        prefix = "supportforge.features",
        name = "kafka-indexing",
        havingValue = "true"
)
public class KnowledgeIndexKafkaListener {

    private final ObjectMapper objectMapper;
    private final KnowledgeIndexingWorker worker;

    public KnowledgeIndexKafkaListener(
            ObjectMapper objectMapper,
            KnowledgeIndexingWorker worker
    ) {
        this.objectMapper = objectMapper;
        this.worker = worker;
    }

    @KafkaListener(
            topics = "${supportforge.kafka.knowledge-topic}",
            groupId = "${supportforge.kafka.consumer-group}",
            containerFactory = "supportForgeKafkaListenerContainerFactory"
    )
    public void onMessage(ConsumerRecord<String, String> record) {
        KnowledgeIndexMessage message = KnowledgeIndexMessage.parse(
                record.value(),
                objectMapper
        );
        worker.process(
                message,
                record.topic(),
                record.partition(),
                record.offset()
        );
    }
}
