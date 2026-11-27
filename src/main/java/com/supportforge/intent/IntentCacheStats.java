package com.supportforge.intent;


public record IntentCacheStats(
        long hits,
        long misses,
        long evictions,
        int size,
        int maxEntries
) {

    /**
     * 本快照对应的已完成缓存查询次数。
     * get每次进入命中/未命中分支之一，故两数相加；put、clear、stats不是查询，不计入。
     */
    public long requests() {
        return hits + misses;
    }


    public double hitRate() {
        long total = requests();

        if (total == 0) {
            return 0.0;
        }

        return (double) hits / total;
    }
}
