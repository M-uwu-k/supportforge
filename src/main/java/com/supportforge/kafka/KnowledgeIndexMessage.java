package com.supportforge.kafka;

import com.supportforge.indexing.KnowledgeIndexPermanentException;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

/** Kafka事件信封中索引Worker实际需要的稳定字段。 */
public record KnowledgeIndexMessage(
        UUID eventId,
        UUID tenantId,
        UUID documentId,
        UUID documentVersionId,
        String externalDocumentId,
        String indexVersion
) {

    public static KnowledgeIndexMessage parse(
            String json,
            ObjectMapper objectMapper
    ) {
        try {
            Map<?, ?> envelope = objectMapper.readValue(json, Map.class);
            Map<?, ?> payload = requireMap(envelope.get("payload"), "payload");
            return new KnowledgeIndexMessage(
                    uuid(envelope.get("event_id"), "event_id"),
                    uuid(payload.get("tenant_id"), "tenant_id"),
                    uuid(payload.get("document_id"), "document_id"),
                    uuid(payload.get("document_version_id"), "document_version_id"),
                    text(payload.get("external_document_id"), "external_document_id"),
                    text(payload.get("index_version"), "index_version")
            );
        } catch (KnowledgeIndexPermanentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new KnowledgeIndexPermanentException(
                    "Kafka知识索引消息不是合法JSON",
                    exception
            );
        }
    }

    private static Map<?, ?> requireMap(Object value, String label) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new KnowledgeIndexPermanentException(label + "必须是对象");
        }
        return map;
    }

    private static UUID uuid(Object value, String label) {
        try {
            return UUID.fromString(text(value, label));
        } catch (IllegalArgumentException exception) {
            throw new KnowledgeIndexPermanentException(label + "必须是UUID", exception);
        }
    }

    private static String text(Object value, String label) {
        if (value == null || value.toString().isBlank()) {
            throw new KnowledgeIndexPermanentException(label + "不能为空");
        }
        return value.toString();
    }
}
