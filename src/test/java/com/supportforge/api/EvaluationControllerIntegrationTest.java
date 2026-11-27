package com.supportforge.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "supportforge.llm.provider=mock",
        "supportforge.knowledge.storage-path=target/test-data/${random.uuid}/knowledge.json",
        "supportforge.evaluation.report-path=target/test-data/${random.uuid}/evaluation-reports.json",
        "supportforge.skills.root=skills"
})
@AutoConfigureMockMvc
class EvaluationControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldEvaluateCustomIntentCasesThroughHttp() throws Exception {
        mockMvc.perform(
                        post("/eval/intent")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "cases": [
                                            {
                                              "message": "你好",
                                              "expected_intent": "greeting"
                                            },
                                            {
                                              "message": "我要退款",
                                              "expected_intent": "refund"
                                            }
                                          ]
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.correct").value(2))
                .andExpect(jsonPath("$.accuracy").value(1.0))
                .andExpect(jsonPath("$.macro_f1").value(1.0))
                .andExpect(jsonPath("$.cases[0].predicted")
                        .value("greeting"))
                .andExpect(jsonPath("$.cases[1].predicted")
                        .value("refund"));
    }

    @Test
    void shouldUseDefaultCasesAndRejectUnknownLabel() throws Exception {
        mockMvc.perform(
                        post("/eval/intent")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(11))
                .andExpect(jsonPath("$.accuracy").isNumber())
                .andExpect(jsonPath("$.per_class").isMap());

        mockMvc.perform(
                        post("/eval/intent")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "cases": [
                                            {
                                              "message": "我要退款",
                                              "expected_intent": "refnud"
                                            }
                                          ]
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("不支持的预期意图：refnud"));
    }

    @Test
    void shouldExposeJudgeFailureSafelyWhenMockDoesNotReturnJson()
            throws Exception {
        mockMvc.perform(
                        post("/eval/judge")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "question": "登录报401怎么办？",
                                          "response": "请重新登录并检查凭证。",
                                          "context": "用户已经重启应用。"
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relevance").value(0.5))
                .andExpect(jsonPath("$.accuracy").value(0.5))
                .andExpect(jsonPath("$.completeness").value(0.5))
                .andExpect(jsonPath("$.helpfulness").value(0.5))
                .andExpect(jsonPath("$.judge_failed").value(true))
                .andExpect(jsonPath("$.error")
                        .value("评判模型没有返回有效JSON"));
    }

    @Test
    void shouldRunEndToEndEvaluationThroughHttp() throws Exception {
        mockMvc.perform(
                        post("/eval/run")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "intent_cases": [
                                            {
                                              "message": "你好",
                                              "expected_intent": "greeting"
                                            }
                                          ],
                                          "dialog_cases": [
                                            {
                                              "question": "你好"
                                            }
                                          ]
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.passed").value(1))
                .andExpect(jsonPath("$.pass_rate").value(0.5))
                .andExpect(jsonPath("$.results[0].test_id")
                        .value("intent_recognition"))
                .andExpect(jsonPath("$.results[1].test_id")
                        .value("dialog_0"))
                .andExpect(jsonPath("$.results[1].passed")
                        .value(false))
                .andExpect(jsonPath(
                        "$.results[1].metadata.judge_failed"
                ).value(true));
    }

    @Test
    void shouldRejectEndToEndRunWithoutCases() throws Exception {
        mockMvc.perform(
                        post("/eval/run")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "intent_cases": [],
                                          "dialog_cases": []
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("至少需要提供一类评测用例"));
    }

    @Test
    void shouldExposeEvaluationHistoryAndBaseline() throws Exception {
        mockMvc.perform(
                        post("/eval/run")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "intent_cases": [
                                            {
                                              "message": "你好",
                                              "expected_intent": "greeting"
                                            }
                                          ],
                                          "dialog_cases": []
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(get("/eval/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].timestamp").exists());

        mockMvc.perform(get("/eval/baseline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.results[0].test_id")
                        .value("intent_recognition"));
    }
}
