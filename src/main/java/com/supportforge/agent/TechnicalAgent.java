package com.supportforge.agent;

import org.springframework.stereotype.Component;

import java.util.List;

import com.supportforge.knowledge.KnowledgeCategory;
@Component
public class TechnicalAgent implements Agent {

    private static final String SPECIALIST_INSTRUCTIONS = """
            你是技术支持 Agent。
            你需要提供安全、可执行、按顺序排列的排查步骤。
            涉及错误码时必须保留错误码。
            不要承诺一定能够修复问题。
            不能要求用户提供密码、验证码或其他敏感信息。
            """;

    private final AgentLlmEnhancer llmEnhancer;

    public TechnicalAgent(
            AgentLlmEnhancer llmEnhancer
    ) {
        this.llmEnhancer = llmEnhancer;
    }

    @Override
    public AgentType type() {
        return AgentType.TECHNICAL;
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
                            KnowledgeCategory.TECHNICAL,
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
                    "技术支持暂时无法处理该请求，请稍后重试。",
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
        List<String> errorCodes =
                request.entities().getOrDefault(
                        "error_code",
                        List.of()
                );

        String errorCodeText = errorCodes.isEmpty()
                ? ""
                : "检测到错误码："
                + String.join("、", errorCodes)
                + "。";

        return switch (request.intent()) {
            case TECHNICAL_LOGIN ->
                    errorCodeText
                            + "请先确认账号输入是否正确，"
                            + "然后尝试清除登录缓存或重置密码。"
                            + "请不要向任何人提供密码或验证码。";

            case TECHNICAL_CRASH ->
                    errorCodeText
                            + "请尝试清除应用缓存、重启应用，"
                            + "并确认当前使用的是最新版本。";

            default ->
                    errorCodeText
                            + "请提供问题发生时间、操作步骤和错误截图，"
                            + "我会继续协助排查。";
        };
    }

    private boolean needsEscalation(String content) {
        return content.contains("转人工")
                || content.contains("人工客服")
                || content.contains("无法处理");
    }
}