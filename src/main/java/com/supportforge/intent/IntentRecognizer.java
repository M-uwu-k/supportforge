package com.supportforge.intent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;

import java.util.Objects;


@Component
public class IntentRecognizer {

    private final EntityExtractor entityExtractor;
    private final LlmIntentRecognizer llmIntentRecognizer;
    private final TemplateEmbeddingIntentRecognizer
            embeddingIntentRecognizer;
    private final PatternIntentRecognizer
            patternIntentRecognizer;
    private final IntentFusion intentFusion;


    private final IntentRecognitionCache intentCache;


    private final boolean legacyCompatibility;


    public IntentRecognizer(
            EntityExtractor entityExtractor
    ) {
        this.entityExtractor =
                Objects.requireNonNull(
                        entityExtractor
                );


        this.llmIntentRecognizer = null;
        this.embeddingIntentRecognizer = null;
        this.patternIntentRecognizer = null;
        this.intentFusion = null;


        this.intentCache =
                new IntentRecognitionCache(
                        1000,
                        500
                );

        this.legacyCompatibility = true;
    }


    public IntentRecognizer(
            EntityExtractor entityExtractor,
            LlmIntentRecognizer llmIntentRecognizer,
            TemplateEmbeddingIntentRecognizer
                    embeddingIntentRecognizer,
            PatternIntentRecognizer
                    patternIntentRecognizer,
            IntentFusion intentFusion
    ) {
        this(
                entityExtractor,
                llmIntentRecognizer,
                embeddingIntentRecognizer,
                patternIntentRecognizer,
                intentFusion,
                new IntentRecognitionCache(
                        1000,
                        500
                )
        );
    }


    @Autowired
    public IntentRecognizer(
            EntityExtractor entityExtractor,
            LlmIntentRecognizer llmIntentRecognizer,
            TemplateEmbeddingIntentRecognizer
                    embeddingIntentRecognizer,
            PatternIntentRecognizer
                    patternIntentRecognizer,
            IntentFusion intentFusion,
            IntentRecognitionCache intentCache
    ) {
        this.entityExtractor =
                Objects.requireNonNull(
                        entityExtractor
                );

        this.llmIntentRecognizer =
                Objects.requireNonNull(
                        llmIntentRecognizer
                );

        this.embeddingIntentRecognizer =
                Objects.requireNonNull(
                        embeddingIntentRecognizer
                );

        this.patternIntentRecognizer =
                Objects.requireNonNull(
                        patternIntentRecognizer
                );

        this.intentFusion =
                Objects.requireNonNull(
                        intentFusion
                );


        this.intentCache =
                Objects.requireNonNull(
                        intentCache
                );

        this.legacyCompatibility = false;
    }


    public IntentResult recognize(
            String message
    ) {
        return recognize(
                message,
                ""
        );
    }


    public IntentResult recognize(
            String message,
            String context
    ) {

        long startTime =
                System.nanoTime();


        if (legacyCompatibility) {
            return recognizeLegacy(
                    message,
                    startTime
            );
        }


        IntentResult cachedResult =
                intentCache.get(
                        message,
                        context
                ).orElse(null);

        if (cachedResult != null) {
            return cachedResult;
        }


        IntentCandidate llmCandidate =
                llmIntentRecognizer.recognize(
                        message,
                        context
                );


        IntentCandidate embeddingCandidate =
                embeddingIntentRecognizer.recognize(
                        message
                );


        IntentCandidate patternCandidate =
                patternIntentRecognizer.recognize(
                        message
                );


        IntentFusionResult fusionResult =
                intentFusion.fuse(
                        llmCandidate,
                        embeddingCandidate,
                        patternCandidate
                );


        IntentCategory finalIntent =
                fusionResult.intent();


        String normalizedMessage =
                normalize(message);


        String intentGroup =
                groupOf(finalIntent);


        UrgencyLevel urgency =
                detectUrgency(
                        normalizedMessage,
                        finalIntent
                );


        Map<String, List<String>> entities =
                entityExtractor.extract(
                        message
                );


        double latencyMs =
                (
                        System.nanoTime()
                                - startTime
                ) / 1_000_000.0;


        IntentResult result =
                new IntentResult(
                        finalIntent,
                        fusionResult.confidence(),
                        urgency,
                        intentGroup,
                        entities,
                        fusionResult.reasoning(),
                        latencyMs,
                        fusionResult.sourceScores()
                );


        intentCache.put(
                message,
                context,
                result
        );


        return result;
    }


    public boolean learn(
            String message,
            IntentCategory correctIntent
    ) {


        if (legacyCompatibility) {
            throw new IllegalStateException(
                    "旧关键词兼容模式不支持意图学习"
            );
        }


        boolean changed =
                embeddingIntentRecognizer.learn(
                        message,
                        correctIntent
                );


        if (changed) {

            intentCache.clear();
        }

        return changed;
    }


    public int learnedTemplateCount() {
        if (legacyCompatibility) {
            return 0;
        }

        return embeddingIntentRecognizer
                .learnedTemplateCount();
    }


    public IntentCacheStats cacheStats() {
        return intentCache.stats();
    }


    public void clearCache() {
        intentCache.clear();
    }


    private IntentResult recognizeLegacy(
            String message,
            long startTime
    ) {
        String normalizedMessage =
                normalize(message);

        IntentCategory intent =
                recognizeByPattern(
                        normalizedMessage
                );

        String intentGroup =
                groupOf(intent);

        UrgencyLevel urgency =
                detectUrgency(
                        normalizedMessage,
                        intent
                );

        double confidence =
                confidenceOf(intent);

        Map<String, List<String>> entities =
                entityExtractor.extract(
                        message
                );

        double latencyMs =
                (
                        System.nanoTime()
                                - startTime
                ) / 1_000_000.0;

        return new IntentResult(
                intent,
                confidence,
                urgency,
                intentGroup,
                entities,
                "通过兼容关键词规则识别为 "
                        + intent.value(),
                latencyMs,
                Map.of(
                        "pattern",
                        confidence
                )
        );
    }


    private IntentCategory recognizeByPattern(String message) {


        if (containsAny(message,
                "转人工", "人工客服", "找人工", "人工处理")) {
            return IntentCategory.HUMAN_HANDOFF;
        }

        if (containsAny(message,
                "无法登录", "登录失败", "登录不了",
                "401", "验证码收不到")) {
            return IntentCategory.TECHNICAL_LOGIN;
        }

        if (containsAny(message,
                "崩溃", "闪退", "500",
                "程序报错", "页面报错", "crash")) {
            return IntentCategory.TECHNICAL_CRASH;
        }

        if (containsAny(message,
                "重复扣款", "多扣", "扣了两次",
                "支付失败", "payment failed")) {
            return IntentCategory.PAYMENT_ISSUE;
        }

        if (containsAny(message,
                "退款", "退货", "refund", "return")) {
            return IntentCategory.REFUND;
        }

        if (containsAny(message,
                "发票", "税号", "发票抬头", "invoice")) {
            return IntentCategory.INVOICE;
        }

        if (containsAny(message,
                "订单状态", "发货了吗", "处理到哪",
                "order status")) {
            return IntentCategory.ORDER_STATUS;
        }

        if (containsAny(message,
                "物流", "快递", "配送", "运单",
                "delivery", "shipping")) {
            return IntentCategory.LOGISTICS;
        }

        if (containsAny(message,
                "账户被盗", "异常登录", "重置密码",
                "两步验证", "账号安全")) {
            return IntentCategory.ACCOUNT_SECURITY;
        }

        if (containsAny(message,
                "投诉", "太差", "糟糕",
                "一直没人处理", "等了很久")) {
            return IntentCategory.COMPLAINT;
        }

        if (containsAny(message,
                "很满意", "服务很棒", "好评", "谢谢你")) {
            return IntentCategory.FEEDBACK;
        }

        if (containsAny(message,
                "你好", "您好", "嗨",
                "hello", "hi", "早上好", "晚上好")) {
            return IntentCategory.GREETING;
        }

        if (containsAny(message,
                "帮我", "请帮", "我需要", "please", "help")) {
            return IntentCategory.REQUEST;
        }

        if (containsAny(message,
                "怎么", "什么", "为什么", "多少",
                "哪里", "是否", "？", "?")) {
            return IntentCategory.QUERY;
        }

        return IntentCategory.OTHER;
    }


    private String groupOf(IntentCategory intent) {
        return switch (intent) {
            case ORDER_STATUS, LOGISTICS ->
                    IntentCategory.QUERY.value();

            case REFUND, INVOICE, PAYMENT_ISSUE ->
                    IntentCategory.BILLING.value();

            case ACCOUNT_SECURITY ->
                    IntentCategory.ACCOUNT.value();

            case TECHNICAL_LOGIN, TECHNICAL_CRASH ->
                    IntentCategory.TECHNICAL.value();

            case HUMAN_HANDOFF ->
                    IntentCategory.ESCALATION.value();

            default -> intent.value();
        };
    }


    private UrgencyLevel detectUrgency(
            String message,
            IntentCategory intent
    ) {
        if (containsAny(message,
                "紧急", "立刻", "emergency", "urgent", "asap")) {
            return UrgencyLevel.CRITICAL;
        }

        if (containsAny(message,
                "今天", "马上", "尽快", "hurry", "now")) {
            return UrgencyLevel.HIGH;
        }

        if (containsAny(message,
                "这周", "本周", "快点", "soon")) {
            return UrgencyLevel.MEDIUM;
        }

        if (intent == IntentCategory.ESCALATION
                || intent == IntentCategory.HUMAN_HANDOFF) {
            return UrgencyLevel.HIGH;
        }

        if (intent == IntentCategory.COMPLAINT) {
            return UrgencyLevel.MEDIUM;
        }

        return UrgencyLevel.LOW;
    }


    private double confidenceOf(IntentCategory intent) {
        return switch (intent) {
            case GREETING, HUMAN_HANDOFF -> 0.95;

            case ORDER_STATUS, LOGISTICS, REFUND,
                    INVOICE, PAYMENT_ISSUE, ACCOUNT_SECURITY,
                    TECHNICAL_LOGIN, TECHNICAL_CRASH -> 0.85;

            case QUERY, REQUEST, COMPLAINT, FEEDBACK -> 0.70;

            case TECHNICAL, BILLING, ACCOUNT, ESCALATION -> 0.65;

            case OTHER -> 0.20;
        };
    }


    private boolean containsAny(String message, String... keywords) {
        for (String keyword : keywords) {
            if (message.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        return false;
    }


    private String normalize(String message) {
        if (message == null) {
            return "";
        }

        return message
                .strip()
                .toLowerCase(Locale.ROOT);
    }
}
