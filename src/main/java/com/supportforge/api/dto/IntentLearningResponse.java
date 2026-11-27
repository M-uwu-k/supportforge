package com.supportforge.api.dto;

import com.supportforge.intent.IntentCacheStats;


public record IntentLearningResponse(
        boolean learned,
        String message,
        String correctIntent,
        int learnedTemplateCount,
        IntentCacheStats cacheStats
) {


}
