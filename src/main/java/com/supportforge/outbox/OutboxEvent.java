package com.supportforge.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 已被当前Publisher租约领取的不可变Outbox事件。 */
public record OutboxEvent(
        UUID id,
        UUID tenantId,
        String aggregateType,
        UUID aggregateId,
        long aggregateVersion,
        String eventType,
        int eventVersion,
        String topic,
        String partitionKey,
        String idempotencyKey,
        Map<String, Object> payload,
        Map<String, Object> headers,
        int attemptCount,
        Instant leaseUntil,
        Instant createdAt
) {

    public OutboxEvent {
        if (id == null || tenantId == null || aggregateId == null) {
            throw new IllegalArgumentException("Outbox事件UUID不能为空");
        }
        requireText(aggregateType, "aggregateType");
        requireText(eventType, "eventType");
        requireText(topic, "topic");
        requireText(partitionKey, "partitionKey");
        requireText(idempotencyKey, "idempotencyKey");
        if (aggregateVersion < 1 || eventVersion < 1 || attemptCount < 1) {
            throw new IllegalArgumentException("Outbox版本和尝试次数必须为正数");
        }
        payload = Map.copyOf(payload);
        headers = Map.copyOf(headers);
        if (leaseUntil == null || createdAt == null) {
            throw new IllegalArgumentException("Outbox租约和创建时间不能为空");
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
    }
}
