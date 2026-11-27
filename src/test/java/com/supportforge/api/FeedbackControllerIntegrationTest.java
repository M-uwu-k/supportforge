package com.supportforge.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "supportforge.llm.provider=mock",
        "supportforge.knowledge.storage-path=target/test-data/${random.uuid}/knowledge.json",
        "supportforge.evaluation.report-path=target/test-data/${random.uuid}/evaluation-reports.json",
        "supportforge.feedback.storage-path=target/test-data/${random.uuid}/feedback.json",
        "supportforge.skills.root=skills"
})
@AutoConfigureMockMvc
class FeedbackControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldSubmitQueryAndSummarizeFeedback() throws Exception {
        String firstResponseId = chat("http-c1", "你好");
        String secondResponseId = chat("http-c1", "登录报401怎么办？");

        submit(firstResponseId, "http-c1", "positive", "解决了问题")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.response_id").value(firstResponseId))
                .andExpect(jsonPath("$.rating").value("positive"));

        submit(secondResponseId, "http-c1", "negative", "步骤不完整")
                .andExpect(status().isOk());

        mockMvc.perform(
                        get("/feedback")
                                .param("conversation_id", "http-c1")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(
                        get("/feedback/summary")
                                .param("conversation_id", "http-c1")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.positive").value(1))
                .andExpect(jsonPath("$.negative").value(1))
                .andExpect(jsonPath("$.satisfaction_rate").value(0.5))
                .andExpect(jsonPath("$.recent_negative_comments[0]")
                        .value("步骤不完整"));
    }

    @Test
    void shouldRejectForgedResponseIdAndUnsupportedRating() throws Exception {
        submit("forged-response", "http-c2", "positive", "伪造")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("没有找到可评价的回答：forged-response"));

        String responseId = chat("http-c2", "你好");
        submit(responseId, "http-c2", "neutral", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("不支持的评价类型：neutral，只支持 positive 或 negative"));
    }

    private String chat(
            String conversationId,
            String message
    ) throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "message": "%s",
                                          "user_id": "feedback-http-user",
                                          "conversation_id": "%s"
                                        }
                                        """.formatted(message, conversationId))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response_id").isNotEmpty())
                .andReturn();

        return JsonPath.read(
                result.getResponse().getContentAsString(),
                "$.response_id"
        );
    }

    private org.springframework.test.web.servlet.ResultActions submit(
            String responseId,
            String conversationId,
            String rating,
            String comment
    ) throws Exception {
        return mockMvc.perform(
                post("/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "response_id": "%s",
                                  "conversation_id": "%s",
                                  "rating": "%s",
                                  "comment": "%s"
                                }
                                """.formatted(
                                responseId,
                                conversationId,
                                rating,
                                comment
                        ))
        );
    }
}
