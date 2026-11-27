package com.supportforge.outbox;

import java.util.List;
import java.util.UUID;

/** Publisher侧Outbox Port；领取与状态变更由数据库租约保护。 */
public interface OutboxRepositoryPort {

    List<OutboxEvent> claim(String owner);

    void markPublished(UUID eventId, String owner);

    void markFailed(UUID eventId, String owner, String errorMessage);
}
