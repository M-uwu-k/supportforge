package com.supportforge.quality;

import com.supportforge.grounding.AnswerGroundingResult;
import com.supportforge.grounding.GroundedAnswerGuard;
import com.supportforge.grounding.GroundingDecision;
import com.supportforge.grounding.GroundingEvidence;
import com.supportforge.grounding.GroundingHarness;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 对真实Grounding流水线运行版本化样本，并把多个质量维度合成为发布门禁。 */
@Service
public class QualityGateService {
    private final GroundingHarness harness;
    private final GroundedAnswerGuard answerGuard;
    private final QualityGateProperties properties;

    public QualityGateService(
            GroundingHarness harness,
            GroundedAnswerGuard answerGuard,
            QualityGateProperties properties
    ) {
        this.harness = harness;
        this.answerGuard = answerGuard;
        this.properties = properties;
    }

    public QualityGateResult evaluate(UUID tenantId, List<QualityGateCase> cases) {
        if (tenantId == null || cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("tenantId和质量样本不能为空");
        }
        List<QualityGateCaseResult> details = new ArrayList<>();
        int expectedPoints = 0;
        int recalledPoints = 0;
        int correctDecisions = 0;
        int expectedAnswers = 0;
        int groundedAnswers = 0;
        int citationClaims = 0;
        int validCitations = 0;
        int expectedRefusals = 0;
        int correctRefusals = 0;

        for (QualityGateCase sample : cases) {
            GroundingDecision decision = harness.ground(
                    tenantId, sample.query(), sample.categories()
            );
            Set<UUID> retrieved = new LinkedHashSet<>();
            decision.evidence().stream().map(GroundingEvidence::pointId)
                    .forEach(retrieved::add);
            long sampleRecalled = sample.expectedPointIds().stream()
                    .filter(retrieved::contains).count();
            expectedPoints += sample.expectedPointIds().size();
            recalledPoints += (int) sampleRecalled;
            boolean decisionCorrect = decision.allowed() == sample.expectedAllowed();
            if (decisionCorrect) {
                correctDecisions++;
            }

            boolean grounded = true;
            List<String> issues = new ArrayList<>();
            if (sample.expectedAllowed()) {
                expectedAnswers++;
                AnswerGroundingResult verification = answerGuard.verify(
                        sample.answer(), decision, sample.citedPointIds()
                );
                grounded = verification.grounded();
                issues.addAll(verification.issues());
                if (grounded) {
                    groundedAnswers++;
                }
                if (sample.citedPointIds().isEmpty()) {

                    citationClaims++;
                } else {
                    citationClaims += sample.citedPointIds().size();
                    validCitations += (int) sample.citedPointIds().stream()
                            .filter(retrieved::contains).count();
                }
            } else {
                expectedRefusals++;
                if (!decision.allowed()) {
                    correctRefusals++;
                } else {
                    issues.add("应拒答但Grounding Harness允许了回答");
                    grounded = false;
                }
            }
            double sampleRecall = sample.expectedPointIds().isEmpty() ? 1.0
                    : sampleRecalled / (double) sample.expectedPointIds().size();
            details.add(new QualityGateCaseResult(
                    sample.id(), sample.expectedAllowed(), decision.allowed(),
                    decisionCorrect, sampleRecall, grounded, retrieved, issues
            ));
        }

        QualityGateMetrics metrics = new QualityGateMetrics(
                cases.size(), ratio(recalledPoints, expectedPoints),
                ratio(correctDecisions, cases.size()),
                ratio(groundedAnswers, expectedAnswers),
                ratio(validCitations, citationClaims),
                ratio(correctRefusals, expectedRefusals)
        );
        List<String> failed = failedMetrics(metrics);
        return new QualityGateResult(
                failed.isEmpty(), metrics, QualityGateThresholds.from(properties),
                failed, details, Instant.now()
        );
    }

    private List<String> failedMetrics(QualityGateMetrics metrics) {
        List<String> failed = new ArrayList<>();
        below(failed, "recall_at_k", metrics.recallAtK(), properties.recallAtK());
        below(failed, "decision_accuracy", metrics.decisionAccuracy(), properties.decisionAccuracy());
        below(failed, "grounded_answer_rate", metrics.groundedAnswerRate(), properties.groundedAnswerRate());
        below(failed, "citation_precision", metrics.citationPrecision(), properties.citationPrecision());
        below(failed, "refusal_accuracy", metrics.refusalAccuracy(), properties.refusalAccuracy());
        return failed;
    }

    private static void below(List<String> failed, String name, double actual, double expected) {
        if (actual + 1e-12 < expected) {
            failed.add(name + "=" + actual + " < " + expected);
        }
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 1.0 : numerator / (double) denominator;
    }
}
