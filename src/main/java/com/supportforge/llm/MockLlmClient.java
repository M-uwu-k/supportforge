package com.supportforge.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;


@Component
@ConditionalOnProperty(
        prefix = "supportforge.llm",
        name = "provider",
        havingValue = "mock",
        matchIfMissing = true
)
public class MockLlmClient implements LlmClient {


    private final String model;


    public MockLlmClient(
            @Value("${supportforge.llm.model:mock-v1}")
            String model
    ) {
        this.model = model;
    }


    @Override
    public LlmResponse generate(LlmRequest request) {
        long startTime = System.nanoTime();


        long historyMessageCount = request.context()
                .lines()
                .filter(line -> !line.isBlank())
                .count();

        long knowledgeCount = request.systemPrompt()
                .lines()
                .map(String::strip)
                .filter(line -> line.startsWith("[知识"))
                .count();

        StringBuilder contentBuilder =
                new StringBuilder("（Mock LLM）");

        if (historyMessageCount == 0) {
            contentBuilder.append("当前没有历史对话。");
        } else {
            contentBuilder.append("已读取 ")
                    .append(historyMessageCount)
                    .append(" 条历史消息。");
        }

        if (knowledgeCount == 0) {
            contentBuilder.append("本次没有读取到知识库内容。");
        } else {
            contentBuilder.append("已读取 ")
                    .append(knowledgeCount)
                    .append(" 条知识库内容。");
        }

        contentBuilder.append("当前问题是：“")
                .append(request.userMessage())
                .append("”。");

        String content = contentBuilder.toString();


        int inputTokens = estimateTokens(
                request.systemPrompt()
                        + request.context()
                        + request.userMessage()
        );

        int outputTokens = estimateTokens(content);


        double latencyMs =
                (System.nanoTime() - startTime) / 1_000_000.0;


        return new LlmResponse(
                content,
                model,
                inputTokens,
                outputTokens,
                latencyMs,
                true
        );
    }


    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }


        return Math.max(1, text.length() / 3);
    }
}
