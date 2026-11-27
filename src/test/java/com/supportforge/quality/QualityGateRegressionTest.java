package com.supportforge.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.supportforge.grounding.GroundedAnswerGuard;
import com.supportforge.grounding.GroundingDecision;
import com.supportforge.grounding.GroundingEvidence;
import com.supportforge.grounding.GroundingHarness;
import com.supportforge.grounding.GroundingRefusalReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** CI发布门禁：固定样本退化时，此测试必须直接失败。 */
@ExtendWith(MockitoExtension.class)
class QualityGateRegressionTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID EXPECTED = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private GroundingHarness harness;
    private QualityGateService service;

    @BeforeEach
    void setUp() {
        service = new QualityGateService(
                harness, new GroundedAnswerGuard(),
                new QualityGateProperties(1, 1, 1, 1, 1)
        );
    }

    @Test
    void versionedEnterpriseDatasetPassesAllReleaseGates() throws Exception {
        when(harness.ground(eq(TENANT), eq("密码重置后多久生效"), any()))
                .thenReturn(allowed(EXPECTED, "密码重置后立即生效"));
        when(harness.ground(eq(TENANT), eq("不存在的产品价格是多少"), any()))
                .thenReturn(refused());

        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        try (InputStream input = getClass().getResourceAsStream(
                "/quality/enterprise-quality-gate.json")) {
            QualityGateRequest dataset = mapper.readValue(input, QualityGateRequest.class);
            QualityGateResult result = service.evaluate(TENANT, dataset.cases());

            assertThat(result.passed()).isTrue();
            assertThat(result.failedMetrics()).isEmpty();
            assertThat(result.metrics().recallAtK()).isEqualTo(1.0);
            assertThat(result.metrics().groundedAnswerRate()).isEqualTo(1.0);
            assertThat(result.metrics().citationPrecision()).isEqualTo(1.0);
            assertThat(result.metrics().refusalAccuracy()).isEqualTo(1.0);
        }
    }

    @Test
    void blocksReleaseWhenRecallAndCitationRegress() {
        UUID wrong = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(harness.ground(eq(TENANT), eq("退化样本"), any()))
                .thenReturn(allowed(wrong, "无关内容"));
        QualityGateCase sample = new QualityGateCase(
                "regression", "退化样本", Set.of(), Set.of(EXPECTED), true,
                "声称已经支持[E1]", Set.of(EXPECTED)
        );

        QualityGateResult result = service.evaluate(TENANT, List.of(sample));

        assertThat(result.passed()).isFalse();
        assertThat(result.failedMetrics())
                .anyMatch(item -> item.startsWith("recall_at_k="))
                .anyMatch(item -> item.startsWith("grounded_answer_rate="))
                .anyMatch(item -> item.startsWith("citation_precision="));
    }

    @Test
    void validRetrievalCannotMakeAnEmptyAnswerPassTheReleaseGate() {
        when(harness.ground(eq(TENANT), eq("空回答回归"), any()))
                .thenReturn(allowed(EXPECTED, "应根据此证据生成非空回答"));
        QualityGateCase sample = new QualityGateCase(
                "empty-answer", "空回答回归", Set.of(), Set.of(EXPECTED), true, "", Set.of(EXPECTED)
        );

        QualityGateResult result = service.evaluate(TENANT, List.of(sample));

        assertThat(result.passed()).isFalse();
        assertThat(result.metrics().recallAtK()).isEqualTo(1.0);
        assertThat(result.metrics().groundedAnswerRate()).isZero();
        assertThat(result.failedMetrics()).anyMatch(item -> item.startsWith("grounded_answer_rate="));
        assertThat(result.caseResults().getFirst().issues()).contains("回答正文不能为空");
    }

    private static GroundingDecision allowed(UUID pointId, String content) {
        GroundingEvidence evidence = new GroundingEvidence(
                "E1", pointId, UUID.randomUUID(), UUID.randomUUID(),
                "quality-doc", 0, content, "sha256-quality", "account", 1, 1
        );
        return new GroundingDecision(
                true, GroundingRefusalReason.NONE, "", 1,
                List.of(evidence), "<evidence>" + content + "</evidence>"
        );
    }

    private static GroundingDecision refused() {
        return new GroundingDecision(
                false, GroundingRefusalReason.NO_EVIDENCE,
                "知识库证据不足", 0, List.of(), ""
        );
    }
}
