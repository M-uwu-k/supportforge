package com.supportforge.agent;

import org.springframework.stereotype.Component;

import java.util.List;

import com.supportforge.knowledge.KnowledgeCategory;
@Component
public class BillingAgent implements Agent {

    private static final String SPECIALIST_INSTRUCTIONS = """
            你是账单与支付支持 Agent。
            必须准确保留订单号和金额。
            不得声称退款、扣款撤销或账户修改已经完成。
            涉及退款、重复扣款和账户安全时，
            必须说明需要核验支付记录或人工审核。
            不能索要密码、短信验证码或银行卡完整信息。
            """;

    private final AgentLlmEnhancer llmEnhancer;

    public BillingAgent(
            AgentLlmEnhancer llmEnhancer
    ) {
        this.llmEnhancer = llmEnhancer;
    }

    @Override
    public AgentType type() {
        return AgentType.BILLING;
    }

    @Override
    public AgentResponse handle(AgentRequest request) {
        long startTime = System.nanoTime();

        try {
            String ruleDraft =
                    buildRuleResponse(request);

            AgentEnhancementResult enhancement =
                    llmEnhancer.enhance(
                            request,
                            type(),
                            KnowledgeCategory.BILLING,
                            SPECIALIST_INSTRUCTIONS,
                            ruleDraft
                    );

            String content = enhancement.content();


            boolean escalate =
                    needsEscalation(ruleDraft)
                            || needsEscalation(content);

            double latencyMs =
                    (System.nanoTime() - startTime)
                            / 1_000_000.0;

            return new AgentResponse(
                    type(),
                    content,
                    true,
                    1.0,
                    latencyMs,
                    escalate,
                    enhancement.knowledgeSources()
            );

        } catch (RuntimeException exception) {
            double latencyMs =
                    (System.nanoTime() - startTime)
                            / 1_000_000.0;

            return new AgentResponse(
                    type(),
                    "账单服务暂时无法处理该请求，请稍后重试。",
                    false,
                    0.0,
                    latencyMs,
                    false,
                    List.of()
            );
        }
    }

    private String buildRuleResponse(
            AgentRequest request
    ) {
        List<String> orderIds =
                request.entities().getOrDefault(
                        "order_id",
                        List.of()
                );

        List<String> amounts =
                request.entities().getOrDefault(
                        "amount",
                        List.of()
                );

        String entityText =
                buildEntityText(orderIds, amounts);

        return switch (request.intent()) {
            case REFUND ->
                    entityText
                            + "退款申请需要核验订单状态和支付记录。"
                            + "涉及实际退款操作时，需要人工审核。";

            case INVOICE ->
                    entityText
                            + "请提供发票类型、发票抬头和税号，"
                            + "我会协助检查开票条件。";

            case PAYMENT_ISSUE ->
                    entityText
                            + "请先确认支付渠道和扣款时间。"
                            + "重复扣款需要核对支付流水，"
                            + "并由人工审核后处理。";

            case ACCOUNT, ACCOUNT_SECURITY ->
                    "该问题涉及账户安全，"
                            + "请不要提供密码或验证码。"
                            + "必要时需要人工客服进一步核验身份。";

            default ->
                    entityText
                            + "请提供订单号、支付方式和具体账单问题。";
        };
    }

    private String buildEntityText(
            List<String> orderIds,
            List<String> amounts
    ) {
        StringBuilder builder = new StringBuilder();

        if (!orderIds.isEmpty()) {
            builder.append("检测到订单号：")
                    .append(String.join("、", orderIds))
                    .append("。");
        }

        if (!amounts.isEmpty()) {
            builder.append("检测到金额：")
                    .append(String.join("、", amounts))
                    .append("。");
        }

        return builder.toString();
    }

    private boolean needsEscalation(String content) {
        return content.contains("人工审核")
                || content.contains("人工客服")
                || content.contains("转人工")
                || content.contains("无法处理");
    }
}