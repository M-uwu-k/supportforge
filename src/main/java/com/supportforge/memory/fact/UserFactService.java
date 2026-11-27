package com.supportforge.memory.fact;

import com.supportforge.cache.TieredCacheService;
import com.supportforge.security.TenantContextProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Clock;
import java.util.List;

@Service
public class UserFactService {

    private final UserFactPort port;
    private final TenantContextProvider tenantContext;
    private final TieredCacheService cache;
    private final Clock clock;

    private final Object[] userLocks = java.util.stream.Stream.generate(Object::new).limit(64).toArray();

    @Autowired
    public UserFactService(
            UserFactPort port,
            TieredCacheService cache,
            TenantContextProvider tenantContext
    ) {
        this(port, cache, tenantContext, Clock.systemUTC());
    }

    public UserFactService(UserFactPort port, TieredCacheService cache,
                           TenantContextProvider tenantContext, Clock clock) {
        this.port = port;
        this.cache = cache;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    public UserFact put(
            String userId, String key, String value, UserFactSource source,
            String sourceReference, double confidence, Instant expiresAt
    ) {
        if (source == UserFactSource.AGENT_INFERENCE
                && (sourceReference == null || sourceReference.isBlank())) {
            throw new IllegalArgumentException("模型推断事实必须提供来源引用");
        }
        String cacheKey = cacheKey(userId);
        synchronized (userLock(cacheKey)) {
            UserFact saved = port.replace(
                    tenantContext.tenantId(), userId, key, value, source, sourceReference,
                    confidence, expiresAt
            );
            cache.invalidate(cacheKey);
            return saved;
        }
    }

    public UserFact correct(
            String userId, String key, String value, String sourceReference
    ) {
        return put(
                userId, key, value, UserFactSource.HUMAN_CORRECTION,
                sourceReference, 1.0, null
        );
    }

    public List<UserFact> active(String userId) {
        String key = cacheKey(userId);
        synchronized (userLock(key)) {
            return activeLocked(userId, key);
        }
    }

    private List<UserFact> activeLocked(String userId, String key) {
        Instant now = clock.instant();
        UserFact[] cached = cache.get(key, UserFact[].class).orElse(null);
        if (cached != null) {

            return java.util.Arrays.stream(cached).filter(fact -> fact.activeAt(now)).toList();
        }
        List<UserFact> facts = port.findActive(tenantContext.tenantId(), userId, now);
        cache.put(key, facts.toArray(UserFact[]::new));
        return facts;
    }

    public boolean revoke(String userId, String key) {
        String cacheKey = cacheKey(userId);
        synchronized (userLock(cacheKey)) {
            boolean revoked = port.revoke(tenantContext.tenantId(), userId, key, clock.instant());
            if (revoked) {
                cache.invalidate(cacheKey);
            }
            return revoked;
        }
    }

    private String cacheKey(String userId) {
        return "tenant:" + tenantContext.tenantId() + ":user-facts:" + userId.strip();
    }

    private Object userLock(String cacheKey) {
        return userLocks[Math.floorMod(cacheKey.hashCode(), userLocks.length)];
    }
}
