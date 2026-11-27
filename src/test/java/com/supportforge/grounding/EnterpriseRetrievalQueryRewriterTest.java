package com.supportforge.grounding;

import com.supportforge.config.LlmGenerationProperties;
import com.supportforge.llm.*;
import com.supportforge.monitor.PerformanceMonitor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EnterpriseRetrievalQueryRewriterTest {
    private final LlmClient client = mock(LlmClient.class);
    private final PerformanceMonitor monitor = mock(PerformanceMonitor.class);
    private final EnterpriseRetrievalQueryRewriter rewriter = new EnterpriseRetrievalQueryRewriter(
            client, new ObjectMapper(), LlmGenerationProperties.defaults(), monitor);

    @Test
    void noHistoryUsesOriginalQuestionWithoutExtraModelCall() {
        assertEquals("登录报错怎么办？", rewriter.rewrite("登录报错怎么办？", ""));
        verifyNoInteractions(client);
    }

    @Test
    void passesFullHistoryToModelAndUsesIndependentRetrievalQuestion() {
        String history = "user: 杉鹭装置灯不亮\nassistant: 先检查电源，再重启装置。";
        LlmResponse response = new LlmResponse("```json\n{\"query\":\"杉鹭装置灯不亮时应该先做什么？\"}\n```",
                "configured-model", 100, 20, 1, true);
        when(client.generate(any())).thenReturn(response);
        assertEquals("杉鹭装置灯不亮时应该先做什么？", rewriter.rewrite("那具体先做什么？", history));
        var request = ArgumentCaptor.forClass(LlmRequest.class);
        verify(client).generate(request.capture());
        assertEquals(history, request.getValue().context());
        assertEquals("那具体先做什么？", request.getValue().userMessage());
        assertEquals(1024, request.getValue().maxTokens());
        verify(monitor).recordLlm(response);
    }

    @Test
    void unusableModelOutputPreservesOriginalQuestion() {
        for (String content : new String[]{"not-json", "{}", "{\"query\":42}", "{\"query\":\"\"}",
                "{\"query\":\"" + "a".repeat(4001) + "\"}"}) {
            when(client.generate(any())).thenReturn(new LlmResponse(content, "model", 1, 1, 1, true));
            assertEquals("这个怎么处理？", rewriter.rewrite("这个怎么处理？", "已有历史"));
        }
    }

    @Test
    void modelFailureDoesNotInventAReplacementQuestion() {
        when(client.generate(any())).thenReturn(new LlmResponse("failed", "model", 1, 0, 1, false))
                .thenThrow(new IllegalStateException("unavailable"));
        assertEquals("那接下来呢？", rewriter.rewrite("那接下来呢？", "已有历史"));
        assertEquals("那接下来呢？", rewriter.rewrite("那接下来呢？", "已有历史"));
    }
}
