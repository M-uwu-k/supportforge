package com.supportforge.agent;

import com.supportforge.intent.IntentCategory;
import com.supportforge.intent.UrgencyLevel;
import com.supportforge.knowledge.KnowledgeCategory;
import com.supportforge.knowledge.KnowledgeSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswerVerifierTest {

    private final AnswerVerifier verifier = new AnswerVerifier();

    @Test
    void shouldVerifyOrdinaryAnswerWithoutKnowledge() {
        AnswerVerifier.VerificationResult verification = verifier.verify(
                request(IntentCategory.GREETING),
                result("你好，请问有什么可以帮助您？", false, List.of())
        );

        assertTrue(verification.verified());
        assertTrue(verification.grounded());
        assertFalse(verification.needsEscalation());
        assertTrue(verification.issues().isEmpty());
    }

    @Test
    void shouldMarkTechnicalAnswerWithoutKnowledgeAsNotGrounded() {
        AnswerVerifier.VerificationResult verification = verifier.verify(
                request(IntentCategory.TECHNICAL_LOGIN),
                result("请重新登录并检查访问令牌。", false, List.of())
        );

        assertTrue(verification.verified());
        assertFalse(verification.grounded());
        assertFalse(verification.needsEscalation());
    }

    @Test
    void shouldRejectUnsafeBusinessCommitment() {
        KnowledgeSource source = new KnowledgeSource(
                "billing-refund",
                "退款申请规则",
                KnowledgeCategory.BILLING,
                2.0,
                List.of("退款")
        );

        AnswerVerifier.VerificationResult verification = verifier.verify(
                request(IntentCategory.REFUND),
                result("已为您完成退款。", false, List.of(source))
        );

        assertFalse(verification.verified());
        assertTrue(verification.grounded());
        assertTrue(verification.needsEscalation());
        assertTrue(
                verification.issues().contains(
                        "回答包含未经真实业务系统确认的操作承诺"
                )
        );
    }

    @Test
    void shouldRejectBlankAnswer() {
        AnswerVerifier.VerificationResult verification = verifier.verify(
                request(IntentCategory.OTHER),
                result("   ", false, List.of())
        );

        assertFalse(verification.verified());
        assertTrue(verification.needsEscalation());
        assertTrue(
                verification.issues().contains(
                        "Agent没有生成有效回答"
                )
        );
    }

    private AgentRequest request(IntentCategory intent) {
        return new AgentRequest(
                "测试消息",
                "test-user",
                "test-conversation",
                "",
                Map.of(),
                intent,
                intent.value(),
                UrgencyLevel.LOW,
                0.9
        );
    }

    private OrchestratorResult result(
            String response,
            boolean escalated,
            List<KnowledgeSource> sources
    ) {
        return new OrchestratorResult(
                response,
                AgentType.GENERAL,
                List.of(AgentType.GENERAL),
                AgentType.GENERAL,
                List.of(),
                "test",
                0.9,
                escalated,
                1.0,
                sources
        );
    }
}
