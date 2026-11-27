package com.supportforge.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "supportforge.llm.provider=mock",
        "supportforge.llm.enhance-specialists=true",
        "supportforge.knowledge.storage-path=target/test-data/${random.uuid}/knowledge.json",
        "supportforge.feedback.storage-path=target/test-data/${random.uuid}/feedback.json"
})
@AutoConfigureMockMvc
class ConversationMemoryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldPreserveAndClearConversationMemory() throws Exception {
        String conversationId = "memory-" + UUID.randomUUID();

        mockMvc.perform(
                        post("/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "message": "你好",
                                          "user_id": "memory-user",
                                          "conversation_id": "%s"
                                        }
                                        """.formatted(conversationId))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation_id").value(conversationId))
                .andExpect(jsonPath("$.intent").value("greeting"));

        mockMvc.perform(
                        post("/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "message": "这个问题应该怎么处理？",
                                          "user_id": "memory-user",
                                          "conversation_id": "%s"
                                        }
                                        """.formatted(conversationId))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation_id").value(conversationId))
                .andExpect(jsonPath("$.intent").value("query"))
                .andExpect(jsonPath("$.agent_type").value("general"))
                .andExpect(jsonPath("$.response").value(containsString("已读取 2 条历史消息")));

        mockMvc.perform(
                        get("/conversations/{conversationId}/messages", conversationId)
                                .param("user_id", "memory-user")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[0].content").value("你好"))
                .andExpect(jsonPath("$[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$[2].role").value("USER"))
                .andExpect(jsonPath("$[2].content").value("这个问题应该怎么处理？"))
                .andExpect(jsonPath("$[3].role").value("ASSISTANT"))
                .andExpect(jsonPath("$[3].content").value(containsString("已读取 2 条历史消息")));

        mockMvc.perform(
                        delete("/conversations/{conversationId}/messages", conversationId)
                                .param("user_id", "memory-user")
                )
                .andExpect(status().isNoContent());

        mockMvc.perform(
                        get("/conversations/{conversationId}/messages", conversationId)
                                .param("user_id", "memory-user")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void shouldIsolateSameConversationIdBetweenUsers() throws Exception {
        String conversationId = "shared-" + UUID.randomUUID();

        sendMessage("isolation-user-a", conversationId, "用户A的问题");
        sendMessage("isolation-user-b", conversationId, "用户B的问题");

        mockMvc.perform(
                        get("/conversations/{conversationId}/messages", conversationId)
                                .param("user_id", "isolation-user-a")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].content").value("用户A的问题"));

        mockMvc.perform(
                        get("/conversations/{conversationId}/messages", conversationId)
                                .param("user_id", "isolation-user-b")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].content").value("用户B的问题"));

        mockMvc.perform(
                        delete("/conversations/{conversationId}/messages", conversationId)
                                .param("user_id", "isolation-user-a")
                )
                .andExpect(status().isNoContent());

        mockMvc.perform(
                        get("/conversations/{conversationId}/messages", conversationId)
                                .param("user_id", "isolation-user-b")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    private void sendMessage(
            String userId,
            String conversationId,
            String message
    ) throws Exception {
        mockMvc.perform(
                        post("/chat")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "message": "%s",
                                          "user_id": "%s",
                                          "conversation_id": "%s"
                                        }
                                        """.formatted(
                                        message,
                                        userId,
                                        conversationId
                                ))
                )
                .andExpect(status().isOk());
    }
}
