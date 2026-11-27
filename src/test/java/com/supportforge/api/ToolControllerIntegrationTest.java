package com.supportforge.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

@SpringBootTest(properties = {
        "supportforge.llm.provider=mock",
        "supportforge.knowledge.storage-path=target/test-data/${random.uuid}/knowledge.json",
        "supportforge.skills.root=skills"
})
@AutoConfigureMockMvc
class ToolControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldListRegisteredTools() throws Exception {
        mockMvc.perform(get("/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name")
                        .value("knowledge_search"))
                .andExpect(jsonPath("$[0].parameters[0].name")
                        .value("query"))
                .andExpect(jsonPath("$[0].parameters[0].required")
                        .value(true))
                .andExpect(jsonPath("$[0].timeout_ms")
                        .value(2000));
    }

    @Test
    void shouldCallKnowledgeSearchThroughToolManager() throws Exception {
        mockMvc.perform(
                        post("/tools/{toolName}/call", "knowledge_search")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "parameters": {
                                            "query": "登录失败401",
                                            "category": "TECHNICAL",
                                            "limit": 2
                                          },
                                          "user_id": "tool-user",
                                          "conversation_id": "tool-conversation"
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.tool_name")
                        .value("knowledge_search"))
                .andExpect(jsonPath("$.data[0].document.id")
                        .value("tech-login-401"))
                .andExpect(jsonPath("$.data[0].matched_keywords")
                        .isNotEmpty())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.latency_ms").isNumber());
    }

    @Test
    void shouldReturnStructuredFailureWithoutCallingUnknownTool() throws Exception {
        mockMvc.perform(
                        post("/tools/{toolName}/call", "system_command")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "parameters": {}
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.tool_name")
                        .value("system_command"))
                .andExpect(jsonPath("$.error")
                        .value("工具不存在：system_command"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void shouldRejectUnknownParameterThroughHttp() throws Exception {
        mockMvc.perform(
                        post("/tools/{toolName}/call", "knowledge_search")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "parameters": {
                                            "query": "退款",
                                            "command": "delete-all"
                                          }
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error")
                        .value("工具 knowledge_search 不支持参数：command"));
    }

    @Test
    void shouldExposeToolStatisticsAndCacheHits() throws Exception {
        String requestBody = """
                {
                  "parameters": {
                    "query": "统计接口缓存验证-唯一问题",
                    "limit": 1
                  },
                  "user_id": "stats-user",
                  "conversation_id": "stats-conversation"
                }
                """;


        mockMvc.perform(
                        post("/tools/{toolName}/call", "knowledge_search")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.cached").value(false));

        mockMvc.perform(
                        post("/tools/{toolName}/call", "knowledge_search")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.cached").value(true));

        mockMvc.perform(get("/tools/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.knowledge_search.total")
                        .value(greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.knowledge_search.successful")
                        .value(greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.knowledge_search.cache_hits")
                        .value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.knowledge_search.circuit_state")
                        .value("CLOSED"));
    }
}
