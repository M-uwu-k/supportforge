package com.supportforge.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "supportforge.llm.provider=mock",
        "supportforge.llm.enhance-specialists=true",
        "supportforge.knowledge.storage-path=target/test-data/${random.uuid}/knowledge.json",
        "supportforge.feedback.storage-path=target/test-data/${random.uuid}/feedback.json"
})
@AutoConfigureMockMvc
class ChatHttpIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldRunCompleteMultiAgentChatFlow() throws Exception {

        mockMvc.perform(
                        post("/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "message": "登录时报错401，同时重复扣款99元",
                                          "user_id": "integration-user",
                                          "conversation_id": "integration-conversation"
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(
                        content().contentTypeCompatibleWith(
                                MediaType.APPLICATION_JSON
                        )
                )


                .andExpect(
                        jsonPath("$.conversation_id")
                                .value("integration-conversation")
                )
                .andExpect(
                        jsonPath("$.conversationId")
                                .doesNotExist()
                )
                .andExpect(
                        jsonPath("$.response_id")
                                .isNotEmpty()
                )


                .andExpect(
                        jsonPath("$.intent")
                                .value("technical_login")
                )
                .andExpect(
                        jsonPath("$.intent_group")
                                .value("technical")
                )


                .andExpect(
                        jsonPath("$.primary_agent")
                                .value("technical")
                )
                .andExpect(
                        jsonPath("$.supporting_agents[0]")
                                .value("billing")
                )
                .andExpect(
                        jsonPath("$.agent_types[0]")
                                .value("technical")
                )
                .andExpect(
                        jsonPath("$.agent_types[1]")
                                .value("billing")
                )


                .andExpect(
                        jsonPath("$.entities.error_code[0]")
                                .value("401")
                )
                .andExpect(
                        jsonPath("$.entities.amount[0]")
                                .value("99元")
                )


                .andExpect(
                        jsonPath("$.knowledge_used")
                                .value(true)
                )
                .andExpect(
                        jsonPath("$.knowledge_sources")
                                .isNotEmpty()
                )
                .andExpect(
                        jsonPath("$.verified")
                                .value(true)
                )
                .andExpect(
                        jsonPath("$.grounded")
                                .value(true)
                )
                .andExpect(
                        jsonPath("$.verification_issues")
                                .isEmpty()
                )


                .andExpect(
                        jsonPath("$.response")
                                .value(
                                        containsString(
                                                "technical - 主处理"
                                        )
                                )
                )
                .andExpect(
                        jsonPath("$.response")
                                .value(
                                        containsString(
                                                "billing - 辅助处理"
                                        )
                                )
                );
    }
}
