package com.supportforge.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "supportforge.llm.provider=mock",
        "supportforge.knowledge.storage-path=target/test-data/${random.uuid}/knowledge.json",
        "supportforge.skills.root=skills"
})
@AutoConfigureMockMvc
class SkillControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldExposeSkillStatusThroughHttp() throws Exception {
        mockMvc.perform(get("/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.root_dir").exists())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.skills.length()")
                        .value(3))
                .andExpect(jsonPath("$.skills[0].enabled")
                        .value(true))
                .andExpect(jsonPath("$.skills[0].content_chars")
                        .isNumber())
                .andExpect(jsonPath("$.skills[0].content")
                        .doesNotExist())
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void shouldReloadSkillsThroughHttp() throws Exception {
        mockMvc.perform(post("/skills/reload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.errors").isEmpty());
    }
}
