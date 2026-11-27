package com.supportforge.intent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


@Component
public class IntentFusion {


    private static final Set<IntentCategory>
            GENERIC_INTENTS =
            EnumSet.of(
                    IntentCategory.QUERY,
                    IntentCategory.BILLING,
                    IntentCategory.TECHNICAL,
                    IntentCategory.ACCOUNT,
                    IntentCategory.ESCALATION
            );


    private static final Set<IntentCategory>
            SPECIFIC_INTENTS =
            EnumSet.of(
                    IntentCategory.ORDER_STATUS,
                    IntentCategory.LOGISTICS,
                    IntentCategory.REFUND,
                    IntentCategory.INVOICE,
                    IntentCategory.PAYMENT_ISSUE,
                    IntentCategory.ACCOUNT_SECURITY,
                    IntentCategory.TECHNICAL_LOGIN,
                    IntentCategory.TECHNICAL_CRASH,
                    IntentCategory.HUMAN_HANDOFF
            );

    private final double llmWeight;
    private final double embeddingWeight;
    private final double patternWeight;
    private final double confidenceThreshold;


    public IntentFusion(
            @Value(
                    "${supportforge.intent.fusion.llm-weight:0.70}"
            )
            double llmWeight,

            @Value(
                    "${supportforge.intent.fusion.embedding-weight:0.20}"
            )
            double embeddingWeight,

            @Value(
                    "${supportforge.intent.fusion.pattern-weight:0.10}"
            )
            double patternWeight,

            @Value(
                    "${supportforge.intent.fusion.confidence-threshold:0.50}"
            )
            double confidenceThreshold
    ) {

        validateWeight("LLM", llmWeight);
        validateWeight("Embedding", embeddingWeight);
        validateWeight("Pattern", patternWeight);


        double weightSum =
                llmWeight
                        + embeddingWeight
                        + patternWeight;


        if (Math.abs(weightSum - 1.0) > 0.000001) {
            throw new IllegalArgumentException(
                    "三路意图识别权重之和必须等于1"
            );
        }


        if (!Double.isFinite(confidenceThreshold)
                || confidenceThreshold < 0.0
                || confidenceThreshold > 1.0) {

            throw new IllegalArgumentException(
                    "意图置信度阈值必须在0到1之间"
            );
        }


        this.llmWeight = llmWeight;
        this.embeddingWeight = embeddingWeight;
        this.patternWeight = patternWeight;
        this.confidenceThreshold =
                confidenceThreshold;
    }


    public static IntentFusion defaults() {
        return new IntentFusion(
                0.70,
                0.20,
                0.10,
                0.50
        );
    }


    public IntentFusionResult fuse(
            IntentCandidate llmCandidate,
            IntentCandidate embeddingCandidate,
            IntentCandidate patternCandidate
    ) {


        IntentCandidate llm =
                safeCandidate(
                        llmCandidate,
                        "LLM没有返回结果"
                );

        IntentCandidate embedding =
                safeCandidate(
                        embeddingCandidate,
                        "Embedding没有返回结果"
                );

        IntentCandidate pattern =
                safeCandidate(
                        patternCandidate,
                        "Pattern没有返回结果"
                );


        Map<String, Double> sourceScores =
                new LinkedHashMap<>();

        sourceScores.put(
                "llm",
                llm.confidence()
        );
        sourceScores.put(
                "embedding",
                embedding.confidence()
        );
        sourceScores.put(
                "pattern",
                pattern.confidence()
        );


        if (llm.failed()) {
            return fallback(
                    embedding,
                    pattern,
                    sourceScores,
                    llm.reasoning()
            );
        }


        Map<IntentCategory, Double> scores =
                new LinkedHashMap<>();

        addScore(
                scores,
                llm,
                llmWeight
        );

        addScore(
                scores,
                embedding,
                embeddingWeight
        );

        addScore(
                scores,
                pattern,
                patternWeight
        );


        IntentCategory bestIntent =
                IntentCategory.OTHER;

        double bestScore = 0.0;

        for (Map.Entry<IntentCategory, Double> entry :
                scores.entrySet()) {


            if (entry.getValue() > bestScore) {
                bestIntent = entry.getKey();
                bestScore = entry.getValue();
            }
        }


        if (shouldRefine(
                bestIntent,
                bestScore,
                pattern
        )) {
            sourceScores.put(
                    "refined_by_pattern",
                    pattern.confidence()
            );


            return new IntentFusionResult(
                    pattern.intent(),
                    Math.max(
                            bestScore,
                            pattern.confidence()
                    ),
                    sourceScores,
                    reasoning(
                            pattern.intent(),
                            llm,
                            embedding,
                            pattern,
                            "关键词规则将宽泛意图细化"
                    )
            );
        }


        if (bestScore < confidenceThreshold) {
            return new IntentFusionResult(
                    IntentCategory.OTHER,
                    bestScore,
                    sourceScores,
                    reasoning(
                            IntentCategory.OTHER,
                            llm,
                            embedding,
                            pattern,
                            "融合分数低于阈值"
                    )
            );
        }


        return new IntentFusionResult(
                bestIntent,
                bestScore,
                sourceScores,
                reasoning(
                        bestIntent,
                        llm,
                        embedding,
                        pattern,
                        "按加权得分选择"
                )
        );
    }

    private IntentFusionResult fallback(
            IntentCandidate embedding,
            IntentCandidate pattern,
            Map<String, Double> sourceScores,
            String llmFailureReason
    ) {
        boolean embeddingUsable =
                isUsable(embedding);

        boolean patternUsable =
                isUsable(pattern);


        if (embeddingUsable
                && embedding.confidence()
                >= confidenceThreshold) {

            return new IntentFusionResult(
                    embedding.intent(),
                    embedding.confidence(),
                    sourceScores,
                    "LLM识别失败，使用高置信度向量结果："
                            + llmFailureReason
            );
        }


        if (patternUsable) {
            String decision =
                    embeddingUsable
                            ? "LLM识别失败且向量置信度低于阈值，使用关键词结果："
                            : "LLM和向量识别不可用，使用关键词结果：";

            return new IntentFusionResult(
                    pattern.intent(),
                    pattern.confidence(),
                    sourceScores,
                    decision + llmFailureReason
            );
        }


        if (embeddingUsable) {
            return new IntentFusionResult(
                    embedding.intent(),
                    embedding.confidence(),
                    sourceScores,
                    "LLM和关键词识别不可用，使用低置信度向量兜底："
                            + llmFailureReason
            );
        }

        return new IntentFusionResult(
                IntentCategory.OTHER,
                0.0,
                sourceScores,
                "三路意图识别均未得到有效结果"
        );
    }


    private void addScore(
            Map<IntentCategory, Double> scores,
            IntentCandidate candidate,
            double weight
    ) {
        if (candidate.failed()) {
            return;
        }

        double weightedScore =
                candidate.confidence()
                        * weight;


        scores.merge(
                candidate.intent(),
                weightedScore,
                Double::sum
        );
    }


    private boolean shouldRefine(
            IntentCategory bestIntent,
            double bestScore,
            IntentCandidate pattern
    ) {
        if (!GENERIC_INTENTS.contains(bestIntent)
                || !SPECIFIC_INTENTS.contains(
                pattern.intent()
        )) {
            return false;
        }

        if (pattern.failed()
                || pattern.confidence() < 0.50
                || bestScore >= 0.80) {
            return false;
        }


        return groupOf(pattern.intent())
                == bestIntent;
    }


    private IntentCategory groupOf(
            IntentCategory intent
    ) {
        return switch (intent) {
            case ORDER_STATUS, LOGISTICS ->
                    IntentCategory.QUERY;

            case REFUND, INVOICE, PAYMENT_ISSUE ->
                    IntentCategory.BILLING;

            case ACCOUNT_SECURITY ->
                    IntentCategory.ACCOUNT;

            case TECHNICAL_LOGIN, TECHNICAL_CRASH ->
                    IntentCategory.TECHNICAL;

            case HUMAN_HANDOFF ->
                    IntentCategory.ESCALATION;

            default -> intent;
        };
    }


    private boolean isUsable(
            IntentCandidate candidate
    ) {
        return !candidate.failed()
                && candidate.intent()
                != IntentCategory.OTHER
                && candidate.confidence() > 0.0;
    }


    private IntentCandidate safeCandidate(
            IntentCandidate candidate,
            String failureReason
    ) {
        return candidate == null
                ? IntentCandidate.failure(
                failureReason
        )
                : candidate;
    }


    private String reasoning(
            IntentCategory selected,
            IntentCandidate llm,
            IntentCandidate embedding,
            IntentCandidate pattern,
            String decision
    ) {
        return String.format(
                Locale.ROOT,
                "%s；最终=%s；LLM=%s(%.3f)，"
                        + "Embedding=%s(%.3f)，"
                        + "Pattern=%s(%.3f)",
                decision,
                selected.value(),
                llm.intent().value(),
                llm.confidence(),
                embedding.intent().value(),
                embedding.confidence(),
                pattern.intent().value(),
                pattern.confidence()
        );
    }


    private void validateWeight(
            String source,
            double weight
    ) {
        if (!Double.isFinite(weight)
                || weight < 0.0
                || weight > 1.0) {

            throw new IllegalArgumentException(
                    source
                            + "意图识别权重必须在0到1之间"
            );
        }
    }
}
