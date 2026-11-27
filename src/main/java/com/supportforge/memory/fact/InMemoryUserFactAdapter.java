package com.supportforge.memory.fact;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        prefix = "supportforge.features", name = "postgres-persistence",
        havingValue = "false", matchIfMissing = true
)
public class InMemoryUserFactAdapter implements UserFactPort {

    private final Map<FactKey, UserFact> active = new LinkedHashMap<>();

    @Override
    public synchronized UserFact replace(
            UUID tenantId, String userId, String key, String value,
            UserFactSource source, String sourceReference,
            double confidence, Instant expiresAt
    ) {
        FactKey factKey = new FactKey(tenantId, userId.strip(), key.strip());
        UserFact previous = active.get(factKey);
        UserFact fact = new UserFact(
                UUID.randomUUID(), tenantId, factKey.userId(), factKey.key(), value,
                source, sourceReference, confidence, Instant.now(), expiresAt,
                previous == null ? null : previous.id()
        );
        active.put(factKey, fact);
        return fact;
    }

    @Override
    public synchronized List<UserFact> findActive(UUID tenantId, String userId, Instant now) {
        List<UserFact> result = new ArrayList<>();
        active.forEach((key, fact) -> {
            if (key.tenantId().equals(tenantId) && key.userId().equals(userId.strip())
                    && fact.activeAt(now)) {
                result.add(fact);
            }
        });
        return List.copyOf(result);
    }

    @Override
    public synchronized boolean revoke(UUID tenantId, String userId, String key, Instant now) {
        return active.remove(new FactKey(tenantId, userId.strip(), key.strip())) != null;
    }

    private record FactKey(UUID tenantId, String userId, String key) { }
}
