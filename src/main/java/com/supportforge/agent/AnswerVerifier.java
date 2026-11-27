package com.supportforge.agent;

import com.supportforge.intent.IntentCategory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;


@Component
public class AnswerVerifier {


    private static final List<String> FAILURE_MARKERS =
            List.of(
                    "所有 Agent 都未能成功处理",
                    "Agent 执行异常",
                    "模型调用失败",
                    "处理失败"
            );


    private static final List<String> UNSAFE_COMMITMENTS =
            List.of(
                    "已为您完成退款",
                    "已经替您退款",
                    "已替您撤销扣款",
                    "已为您修改账户",
                    "已为您重置密码",
                    "已经为您转账"
            );


    public VerificationResult verify(
            AgentRequest request,
            OrchestratorResult result
    ) {

        List<String> issues = new ArrayList<>();


        String response =
                result.response() == null
                        ? ""
                        : result.response().strip();


        if (response.isBlank()) {

            issues.add("Agent没有生成有效回答");
        }


        if (containsAny(response, FAILURE_MARKERS)) {
            issues.add("回答中包含系统执行失败信息");
        }


        if (containsAny(response, UNSAFE_COMMITMENTS)) {
            issues.add("回答包含未经真实业务系统确认的操作承诺");
        }


        boolean requiresKnowledge =
                requiresKnowledge(request.intent());


        boolean hasKnowledge =
                result.knowledgeSources() != null
                        && !result.knowledgeSources().isEmpty();


        boolean grounded =
                !requiresKnowledge || hasKnowledge;


        if (result.grounding() != null) {
            grounded = result.grounding().grounded();
            issues.addAll(result.grounding().issues());
        }


        boolean verified = issues.isEmpty();


        boolean needsEscalation =
                result.escalated() || !verified;


        return new VerificationResult(
                verified,
                grounded,
                needsEscalation,
                List.copyOf(issues)
        );
    }


    private boolean requiresKnowledge(
            IntentCategory intent
    ) {

        if (intent == null) {
            return false;
        }

        return switch (intent) {

            case ORDER_STATUS,
                    LOGISTICS,
                    REFUND,
                    INVOICE,
                    PAYMENT_ISSUE,
                    ACCOUNT_SECURITY,
                    TECHNICAL,
                    TECHNICAL_LOGIN,
                    TECHNICAL_CRASH,
                    BILLING,
                    ACCOUNT -> true;


            case GREETING,
                    QUERY,
                    REQUEST,
                    COMPLAINT,
                    FEEDBACK,
                    ESCALATION,
                    HUMAN_HANDOFF,
                    OTHER -> false;
        };
    }


    private boolean containsAny(
            String response,
            List<String> markers
    ) {
        for (String marker : markers) {


            if (response.contains(marker)) {

                return true;
            }
        }

        return false;
    }


    public record VerificationResult(
            boolean verified,
            boolean grounded,
            boolean needsEscalation,
            List<String> issues
    ) {

    }
}
