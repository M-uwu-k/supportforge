package com.supportforge.intent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentRecognitionCacheTest {

    @Test
    void shouldSeparateSameMessageByContextAndCountHits() {
        IntentRecognitionCache cache =
                new IntentRecognitionCache(10, 5);
        IntentResult result = result(IntentCategory.REFUND);

        assertTrue(cache.get("还是那个问题", "退款上下文").isEmpty());
        cache.put("还是那个问题", "退款上下文", result);

        IntentResult cached = cache.get(
                "还是那个问题",
                "退款上下文"
        ).orElseThrow();

        assertEquals(IntentCategory.REFUND, cached.intent());
        assertTrue(cache.get("还是那个问题", "登录上下文").isEmpty());

        IntentCacheStats stats = cache.stats();
        assertEquals(1, stats.hits());
        assertEquals(2, stats.misses());
        assertEquals(3, stats.requests());
        assertEquals(1.0 / 3.0, stats.hitRate(), 0.000001);
        assertEquals(1, stats.size());
    }

    @Test
    void shouldNormalizeOuterWhitespaceAndNullContext() {
        IntentRecognitionCache cache =
                new IntentRecognitionCache(10, 5);

        cache.put(
                "  我要退款  ",
                null,
                result(IntentCategory.REFUND)
        );

        assertTrue(cache.get("我要退款", "").isPresent());
        assertTrue(cache.get(" 我要退款 ", "   ").isPresent());
        assertEquals(2, cache.stats().hits());
    }

    @Test
    void shouldEvictOldestBatchWhenCapacityIsReached() {
        IntentRecognitionCache cache =
                new IntentRecognitionCache(3, 2);

        cache.put("A", "", result(IntentCategory.QUERY));
        cache.put("B", "", result(IntentCategory.REFUND));
        cache.put("C", "", result(IntentCategory.GREETING));
        cache.put("D", "", result(IntentCategory.TECHNICAL));

        assertFalse(cache.get("A", "").isPresent());
        assertFalse(cache.get("B", "").isPresent());
        assertTrue(cache.get("C", "").isPresent());
        assertTrue(cache.get("D", "").isPresent());
        assertEquals(2, cache.stats().size());
        assertEquals(2, cache.stats().evictions());
    }

    @Test
    void shouldStoreDefensiveImmutableCopyAndClearEntries() {
        IntentRecognitionCache cache =
                new IntentRecognitionCache(10, 5);
        Map<String, List<String>> entities = new LinkedHashMap<>();
        List<String> orderIds = new ArrayList<>(List.of("A12345"));
        entities.put("order_id", orderIds);
        Map<String, Double> sourceScores = new LinkedHashMap<>();
        sourceScores.put("pattern", 0.75);

        IntentResult original = new IntentResult(
                IntentCategory.ORDER_STATUS,
                0.75,
                UrgencyLevel.LOW,
                "query",
                entities,
                "测试",
                1.0,
                sourceScores
        );

        cache.put("查订单", "", original);
        orderIds.add("B67890");
        sourceScores.put("llm", 0.90);

        IntentResult cached =
                cache.get("查订单", "").orElseThrow();

        assertNotSame(original, cached);
        assertEquals(List.of("A12345"), cached.entities().get("order_id"));
        assertEquals(Map.of("pattern", 0.75), cached.sourceScores());
        assertThrows(
                UnsupportedOperationException.class,
                () -> cached.entities().put("amount", List.of("99元"))
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> cached.entities().get("order_id").add("C00000")
        );

        cache.clear();
        assertEquals(0, cache.stats().size());
        assertTrue(cache.get("查订单", "").isEmpty());
    }

    @Test
    void shouldValidateCapacitySettings() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new IntentRecognitionCache(0, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new IntentRecognitionCache(10, 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new IntentRecognitionCache(10, 11)
        );
    }

    private IntentResult result(IntentCategory intent) {
        return new IntentResult(
                intent,
                0.75,
                UrgencyLevel.LOW,
                intent.value(),
                Map.of(),
                "测试结果",
                1.0,
                Map.of("pattern", 0.75)
        );
    }
}
