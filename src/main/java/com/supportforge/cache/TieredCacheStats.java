package com.supportforge.cache;

public record TieredCacheStats(long l1Hits, long l2Hits, long misses, long invalidations) { }
