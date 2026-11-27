package com.supportforge.intent;

import com.supportforge.config.LlmGenerationProperties;
import org.springframework.beans.factory.annotation.Autowired;

import com.supportforge.llm.LlmClient;
import com.supportforge.llm.LlmRequest;
import com.supportforge.llm.LlmResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.stream.Collectors;


@Component
public class LlmIntentRecognizer {


    private static final double TEMPERATURE =
            0.1;


    private LlmGenerationProperties generation =
            LlmGenerationProperties.defaults();

    @Autowired
    void configureGeneration(LlmGenerationProperties generation) {
        this.generation = generation;
    }


    private static final String SYSTEM_PROMPT =
            createSystemPrompt();

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;


    public LlmIntentRecognizer(
            LlmClient llmClient,
            ObjectMapper objectMapper,

            @Value(
                    "${supportforge.intent.llm.enabled:true}"
            )
            boolean enabled
    ) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }


    public IntentCandidate recognize(
            String message
    ) {
        return recognize(
                message,
                ""
        );
    }


    public IntentCandidate recognize(
            String message,
            String context
    ) {

        String safeMessage =
                message == null
                        ? ""
                        : message.strip();


        String safeContext =
                context == null
                        ? ""
                        : context.strip();


        if (!enabled) {
            return IntentCandidate.failure(
                    "LLM意图识别已关闭"
            );
        }


        if (safeMessage.isBlank()) {
            return IntentCandidate.success(
                    IntentCategory.OTHER,
                    0.0,
                    "用户消息为空"
            );
        }

        try {

            LlmRequest request =
                    new LlmRequest(
                            SYSTEM_PROMPT,
                            safeMessage,
                            safeContext,
                            TEMPERATURE,
                            generation.intentMaxTokens()
                    );


            LlmResponse response =
                    llmClient.generate(request);


            if (response == null) {
                return IntentCandidate.failure(
                        "LLM客户端没有返回结果"
                );
            }


            if (!response.success()) {
                return IntentCandidate.failure(
                        "LLM客户端返回失败状态"
                );
            }

            if (response.content() == null
                    || response.content().isBlank()) {

                return IntentCandidate.failure(
                        "LLM返回内容为空"
                );
            }


            String json =
                    extractJson(
                            response.content()
                    );


            LlmIntentPayload payload =
                    objectMapper.readValue(
                            json,
                            LlmIntentPayload.class
                    );


            IntentCategory intent =
                    IntentCategory.fromValue(
                            payload.intent()
                    );


            double confidence =
                    payload.confidence() == null
                            ? 0.0
                            : payload.confidence();


            String reasoning =
                    payload.reasoning() == null
                            ? ""
                            : payload.reasoning();


            return IntentCandidate.success(
                    intent,
                    confidence,
                    reasoning
            );
        } catch (Exception exception) {


            return IntentCandidate.failure(
                    "LLM意图识别失败："
                            + exception.getMessage()
            );
        }
    }


    private String extractJson(
            String content
    ) {

        int start =
                content.indexOf('{');

        int end =
                content.lastIndexOf('}');

        if (start < 0
                || end < start) {

            throw new IllegalArgumentException(
                    "模型回答中没有合法JSON对象"
            );
        }


        return content.substring(
                start,
                end + 1
        );
    }


    private static String createSystemPrompt() {


        String intentValues =
                Arrays.stream(
                                IntentCategory.values()
                        )
                        .map(
                                IntentCategory::value
                        )
                        .collect(
                                Collectors.joining(", ")
                        );


        return """
                你是智能客服系统的意图分类器。

                你的任务只是判断用户意图，不要回答用户的问题，
                不要执行退款、取消订单或其他业务操作。

                请优先返回细粒度业务意图：

                - order_status：查询订单状态或处理进度
                - logistics：物流、快递、配送问题
                - refund：退款或退货
                - invoice：发票、抬头、税号问题
                - payment_issue：重复扣款、支付失败等支付异常
                - account_security：账户被盗、异常登录、安全问题
                - technical_login：登录失败、401、验证码问题
                - technical_crash：崩溃、闪退、500错误
                - human_handoff：要求人工客服或升级处理

                如果没有合适的细粒度意图，再选择宽泛意图：

                - query：一般信息查询
                - complaint：表达不满
                - request：请求帮助或操作
                - greeting：问候
                - escalation：投诉或要求升级
                - technical：一般技术问题
                - billing：一般账单问题
                - account：一般账户问题
                - feedback：正面反馈
                - other：无法判断

                示例：

                用户：我的订单处理到哪里了？
                输出：
                {"intent":"order_status","confidence":0.95,
                "reasoning":"用户询问订单处理进度"}

                用户：为什么扣了两次钱？
                输出：
                {"intent":"payment_issue","confidence":0.96,
                "reasoning":"用户反馈重复扣款"}

                用户：登录一直报401
                输出：
                {"intent":"technical_login","confidence":0.94,
                "reasoning":"用户遇到登录认证错误"}

                用户：我要退款
                输出：
                {"intent":"refund","confidence":0.97,
                "reasoning":"用户明确提出退款"}

                只返回一个JSON对象，不要使用Markdown代码块，
                不要添加JSON之外的说明文字。

                JSON格式必须是：

                {"intent":"意图值",
                "confidence":0到1之间的数字,
                "reasoning":"一句话判断原因"}

                可选意图：
                """
                + intentValues;
    }


    private record LlmIntentPayload(
            String intent,
            Double confidence,
            String reasoning
    ) {
    }
}
