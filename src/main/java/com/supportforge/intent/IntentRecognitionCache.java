package com.supportforge.intent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;


@Component
public class IntentRecognitionCache {

    private final int maxEntries;
    private final int trimEntries;


    private final Map<String, IntentResult> entries =
            new LinkedHashMap<>();

    private long hits;
    private long misses;
    private long evictions;


    public IntentRecognitionCache(
            @Value(
                    "${supportforge.intent.cache.max-entries:1000}"
            )
            int maxEntries,

            @Value(
                    "${supportforge.intent.cache.trim-entries:500}"
            )
            int trimEntries
    ) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException(
                    "意图缓存最大容量必须至少为1"
            );
        }

        if (trimEntries < 1
                || trimEntries > maxEntries) {

            throw new IllegalArgumentException(
                    "意图缓存批量淘汰数量必须在1到最大容量之间"
            );
        }

        this.maxEntries = maxEntries;
        this.trimEntries = trimEntries;
    }


    public synchronized Optional<IntentResult> get(
            String message,
            String context
    ) {

        String key = cacheKey(message, context);
        IntentResult result = entries.get(key);

        if (result == null) {
            misses++;
            return Optional.empty();
        }

        hits++;
        return Optional.of(result);
    }


    public synchronized void put(
            String message,
            String context,
            IntentResult result
    ) {

        Objects.requireNonNull(
                result,
                "缓存的意图识别结果不能为null"
        );

        String key = cacheKey(message, context);


        if (entries.containsKey(key)) {
            entries.put(
                    key,
                    immutableCopy(result)
            );
            return;
        }


        if (entries.size() >= maxEntries) {
            evictOldestBatch();
        }


        entries.put(
                key,
                immutableCopy(result)
        );
    }


    public synchronized void clear() {
        entries.clear();
    }


    public synchronized IntentCacheStats stats() {
        return new IntentCacheStats(
                hits,
                misses,
                evictions,
                entries.size(),
                maxEntries
        );
    }


    private void evictOldestBatch() {

        Iterator<String> iterator =
                entries.keySet().iterator();

        int removed = 0;

        while (iterator.hasNext()
                && removed < trimEntries) {

            iterator.next();
            iterator.remove();
            removed++;
        }

        evictions += removed;
    }


    private IntentResult immutableCopy(
            IntentResult source
    ) {

        Map<String, List<String>> entities =
                new LinkedHashMap<>();

        if (source.entities() != null) {


            source.entities().forEach(
                    (name, values) -> entities.put(
                            name,
                            values == null
                                    ? List.of()
                                    : List.copyOf(values)
                    )
            );
        }


        Map<String, Double> sourceScores =
                new LinkedHashMap<>();

        if (source.sourceScores() != null) {
            sourceScores.putAll(
                    source.sourceScores()
            );
        }


        return new IntentResult(
                source.intent(),
                source.confidence(),
                source.urgency(),
                source.intentGroup(),
                Collections.unmodifiableMap(
                        entities
                ),
                source.reasoning(),
                source.latencyMs(),
                Collections.unmodifiableMap(
                        sourceScores
                )
        );
    }


    private String cacheKey(
            String message,
            String context
    ) {


        String safeMessage = normalize(message);
        String safeContext = normalize(context);


        String rawKey =
                safeMessage.length()
                        + ":"
                        + safeMessage
                        + "|"
                        + safeContext.length()
                        + ":"
                        + safeContext;


        String tenantScope = com.supportforge.security.TenantContext.current()
                .map(identity -> identity.tenantId().toString()).orElse("");
        if (!tenantScope.isEmpty()) rawKey = tenantScope + "|" + rawKey;

        try {


            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256"
                    );


            byte[] hash = digest.digest(
                    rawKey.getBytes(
                            StandardCharsets.UTF_8
                    )
            );


            return HexFormat.of()
                    .formatHex(hash);

        } catch (NoSuchAlgorithmException exception) {


            throw new IllegalStateException(
                    "当前Java运行环境不支持SHA-256",
                    exception
            );
        }
    }

    /**
     * null变为空字符串，并忽略首尾空白差异。
     * null、""、"   "在这里都规范为""；"REFUND"与"refund"仍然不同，不会自动转小写或压缩内部空白。
     */
    private String normalize(String value) {
        return value == null
                ? ""
                : value.strip();
    }
}
