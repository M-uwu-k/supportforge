package com.supportforge.api;

import com.supportforge.intent.IntentRecognizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@SpringBootTest(properties = {
        "supportforge.llm.provider=mock",
        "supportforge.intent.cache.max-entries=20",
        "supportforge.intent.cache.trim-entries=10",

        "supportforge.intent.learning.storage-path=target/test-data/${random.uuid}/intent-learning.json",
        "supportforge.knowledge.storage-path=target/test-data/${random.uuid}/knowledge.json",
        "supportforge.feedback.storage-path=target/test-data/${random.uuid}/feedback.json"
})
@AutoConfigureMockMvc
class IntentControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IntentRecognizer intentRecognizer;


    @Test
    void shouldLearnIntentAndReportDuplicateSubmission() throws Exception {
        String body = """
                {
                  "message": "HTTP学习样本-退款-9911",
                  "correct_intent": "refund"
                }
                """;


        mockMvc.perform(
                        post("/intents/learn")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.learned").value(true))
                .andExpect(jsonPath("$.message")
                        .value("HTTP学习样本-退款-9911"))
                .andExpect(jsonPath("$.correct_intent").value("refund"))
                .andExpect(jsonPath("$.learned_template_count").isNumber())
                .andExpect(jsonPath("$.cache_stats.size").value(0));


        mockMvc.perform(
                        post("/intents/learn")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.learned").value(false))
                .andExpect(jsonPath("$.correct_intent").value("refund"));
    }


    @Test
    void shouldRejectUnknownOrOtherIntentLabels() throws Exception {
        mockMvc.perform(
                        post("/intents/learn")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "message": "无效标签样本",
                                          "correct_intent": "not_real_intent"
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/intents/learn"));

        mockMvc.perform(
                        post("/intents/learn")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "message": "不能学习OTHER",
                                          "correct_intent": "other"
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest());
    }


    @Test
    void shouldExposeAndClearIntentCache() throws Exception {
        intentRecognizer.clearCache();
        intentRecognizer.recognize(
                "HTTP缓存统计样本-7788",
                "固定测试上下文"
        );

        mockMvc.perform(get("/intents/cache/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.max_entries").value(20));

        mockMvc.perform(delete("/intents/cache"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/intents/cache/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(0));
    }
}
