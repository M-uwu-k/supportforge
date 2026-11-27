package com.supportforge.api;

import com.supportforge.knowledge.KnowledgeBase;
import com.supportforge.knowledge.ingestion.NoOpKnowledgeIngestionAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "supportforge.llm.provider=mock",
        "supportforge.llm.enhance-specialists=true",
        "supportforge.knowledge.storage-path=target/test-data/${random.uuid}/knowledge.json"
})
@AutoConfigureMockMvc
class KnowledgeControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KnowledgeBase knowledgeBase;

    @MockitoSpyBean
    private NoOpKnowledgeIngestionAdapter ingestionPersistence;

    @Test
    void shouldBrowseWithoutSearchTermsAndReadFullDocument() throws Exception {
        var document = knowledgeBase.readableDocuments().getFirst();
        mockMvc.perform(get("/knowledge/documents").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_elements").value(knowledgeBase.count()))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.documents.length()").value(2));
        mockMvc.perform(get("/knowledge/documents/{id}", document.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(document.id()))
                .andExpect(jsonPath("$.content").value(document.content()));
        mockMvc.perform(get("/knowledge/documents").param("page", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/knowledge/documents/{id}", "missing-browse-document"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldChunkManualKnowledgeAndKeepDuplicateCreationAsConflict() throws Exception {
        String id = "manual-ingestion-" + UUID.randomUUID();
        String body = """
                {
                  "id": "%s",
                  "title": "手动新增的企业知识",
                  "category": "TECHNICAL",
                  "content": "手动新增的正文必须进入知识分块流水线。",
                  "keywords": ["手动新增"]
                }
                """.formatted(id);

        mockMvc.perform(post("/knowledge/documents")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/knowledge/documents")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());

        verify(ingestionPersistence, times(1)).replaceChunks(
                eq(id), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                argThat(chunks -> !chunks.isEmpty()
                        && chunks.getFirst().content().contains("手动新增的正文"))
        );
    }

    @Test
    void shouldAddCountAndSearchKnowledgeThroughHttp() throws Exception {
        int before = knowledgeBase.count();
        String suffix = UUID.randomUUID()
                .toString()
                .replace("-", "");
        String documentId = "runtime-" + suffix;
        String keyword = "动态知识" + suffix;

        mockMvc.perform(
                        post("/knowledge/documents")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "id": "%s",
                                          "title": "运行时知识",
                                          "category": "BILLING",
                                          "content": "这是一篇运行时添加的知识资料。",
                                          "keywords": ["%s"]
                                        }
                                        """.formatted(documentId, keyword))
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(documentId))
                .andExpect(jsonPath("$.title").value("运行时知识"))
                .andExpect(jsonPath("$.category").value("BILLING"))
                .andExpect(jsonPath("$.keywords[0]").value(keyword));

        assertEquals(before + 1, knowledgeBase.count());

        mockMvc.perform(get("/knowledge/stats"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.total_documents")
                                .value(before + 1)
                );

        mockMvc.perform(
                        get("/knowledge/search")
                                .param("query", keyword)
                                .param("category", "BILLING")
                                .param("limit", "3")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].document.id").value(documentId))
                .andExpect(jsonPath("$[0].matched_keywords[0]").value(keyword));
    }

    @Test
    void shouldRejectDocumentWithoutKeywords() throws Exception {
        mockMvc.perform(
                        post("/knowledge/documents")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "title": "无关键词文档",
                                          "category": "GENERAL",
                                          "content": "正文存在，但关键词为空。",
                                          "keywords": []
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("keywords: keywords 至少包含一个关键词"))
                .andExpect(jsonPath("$.path").value("/knowledge/documents"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void shouldDeleteDynamicDocumentThroughHttp() throws Exception {
        int before = knowledgeBase.count();
        String suffix = UUID.randomUUID()
                .toString()
                .replace("-", "");
        String documentId = "delete-" + suffix;
        String keyword = "删除验证" + suffix;

        mockMvc.perform(
                        post("/knowledge/documents")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "id": "%s",
                                          "title": "待删除知识",
                                          "category": "GENERAL",
                                          "content": "用于验证删除接口和删除后的搜索结果。",
                                          "keywords": ["%s"]
                                        }
                                        """.formatted(documentId, keyword))
                )
                .andExpect(status().isCreated());

        assertEquals(before + 1, knowledgeBase.count());

        mockMvc.perform(
                        delete(
                                "/knowledge/documents/{documentId}",
                                documentId
                        )
                )
                .andExpect(status().isNoContent());

        assertEquals(before, knowledgeBase.count());

        mockMvc.perform(
                        get("/knowledge/search")
                                .param("query", keyword)
                                .param("limit", "3")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].document.id")
                        .value(not(hasItem(documentId))));
    }

    @Test
    void shouldReturnNotFoundForMissingDocument() throws Exception {
        String documentId = "missing-" + UUID.randomUUID();

        mockMvc.perform(
                        delete(
                                "/knowledge/documents/{documentId}",
                                documentId
                        )
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message")
                        .value("知识文档不存在：" + documentId))
                .andExpect(jsonPath("$.path")
                        .value("/knowledge/documents/" + documentId))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void shouldRejectDeletingBuiltInDocument() throws Exception {
        mockMvc.perform(
                        delete(
                                "/knowledge/documents/{documentId}",
                                "billing-refund"
                        )
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message")
                        .value("内置知识文档不能删除：billing-refund"));
    }

    @Test
    void shouldReturnConflictForDuplicateDocumentId() throws Exception {
        String documentId = "duplicate-" + UUID.randomUUID();
        String body = """
                {
                  "id": "%s",
                  "title": "重复ID测试",
                  "category": "GENERAL",
                  "content": "第一次添加成功，第二次添加应当返回冲突。",
                  "keywords": ["重复ID测试"]
                }
                """.formatted(documentId);

        mockMvc.perform(
                        post("/knowledge/documents")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                )
                .andExpect(status().isCreated());

        mockMvc.perform(
                        post("/knowledge/documents")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message")
                        .value("知识文档ID已存在：" + documentId));

        mockMvc.perform(
                        delete(
                                "/knowledge/documents/{documentId}",
                                documentId
                        )
                )
                .andExpect(status().isNoContent());
    }

    @Test
    void shouldReturnBadRequestForInvalidCategory() throws Exception {
        mockMvc.perform(
                        get("/knowledge/search")
                                .param("query", "退款")
                                .param("category", "UNKNOWN")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message")
                        .value("请求参数缺失、格式错误或字段类型不正确"))
                .andExpect(jsonPath("$.path")
                        .value("/knowledge/search"));
    }

    @Test
    void shouldEvictKnowledgeToolCacheAfterAddAndDelete() throws Exception {
        String suffix = UUID.randomUUID()
                .toString()
                .replace("-", "");
        String documentId = "cache-invalidation-" + suffix;
        String keyword = "缓存失效验证" + suffix;
        String toolRequest = """
                {
                  "parameters": {
                    "query": "%s",
                    "category": "GENERAL",
                    "limit": 3
                  },
                  "user_id": "cache-user",
                  "conversation_id": "cache-conversation"
                }
                """.formatted(keyword);


        mockMvc.perform(
                        post("/tools/{toolName}/call", "knowledge_search")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(toolRequest)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.data[*].document.id")
                        .value(not(hasItem(documentId))));

        mockMvc.perform(
                        post("/knowledge/documents")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "id": "%s",
                                          "title": "缓存失效测试知识",
                                          "category": "GENERAL",
                                          "content": "新增后必须立即被工具检索到。",
                                          "keywords": ["%s"]
                                        }
                                        """.formatted(documentId, keyword))
                )
                .andExpect(status().isCreated());


        mockMvc.perform(
                        post("/tools/{toolName}/call", "knowledge_search")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(toolRequest)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.data[0].document.id")
                        .value(documentId));


        mockMvc.perform(
                        post("/tools/{toolName}/call", "knowledge_search")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(toolRequest)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(true));

        mockMvc.perform(
                        delete(
                                "/knowledge/documents/{documentId}",
                                documentId
                        )
                )
                .andExpect(status().isNoContent());


        mockMvc.perform(
                        post("/tools/{toolName}/call", "knowledge_search")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(toolRequest)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.data[*].document.id")
                        .value(not(hasItem(documentId))));
    }


    @Test
    void shouldBatchImportValidItemsAndReportInvalidItems() throws Exception {
        int before = knowledgeBase.count();
        String suffix = UUID.randomUUID()
                .toString()
                .replace("-", "");
        String documentId = "batch-" + suffix;

        mockMvc.perform(
                        post("/knowledge/batch")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "default_category": "TECHNICAL",
                                          "documents": [
                                            {
                                              "id": "%s",
                                              "title": "批量导入知识",
                                              "content": "批量导入的有效知识正文。",
                                              "keywords": ["批量导入"]
                                            },
                                            {
                                              "title": "   ",
                                              "content": "标题为空的无效知识。"
                                            }
                                          ]
                                        }
                                        """.formatted(documentId))
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.documents[0].id")
                        .value(documentId))
                .andExpect(jsonPath("$.documents[0].category")
                        .value("TECHNICAL"))
                .andExpect(jsonPath("$.errors[0]").exists());

        assertEquals(before + 1, knowledgeBase.count());
    }


    @Test
    void shouldUploadMarkdownKnowledgeThroughHttp() throws Exception {
        String suffix = UUID.randomUUID()
                .toString()
                .replace("-", "");
        String title = "上传知识" + suffix;
        String content = "# " + title
                + "\n\n这是一篇通过 multipart 上传的 Markdown 知识。";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "uploaded-knowledge.md",
                "text/markdown",
                content.getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(
                        multipart("/knowledge/upload")
                                .file(file)
                                .param("category", "BILLING")
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.documents[0].title")
                        .value(title))
                .andExpect(jsonPath("$.documents[0].category")
                        .value("BILLING"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }


    @Test
    void shouldEvictKnowledgeToolCacheAfterBatchImport() throws Exception {
        String suffix = UUID.randomUUID()
                .toString()
                .replace("-", "");
        String documentId = "batch-cache-" + suffix;
        String keyword = "批量缓存失效" + suffix;
        String toolRequest = """
                {
                  "parameters": {
                    "query": "%s",
                    "category": "GENERAL",
                    "limit": 3
                  },
                  "user_id": "batch-cache-user",
                  "conversation_id": "batch-cache-conversation"
                }
                """.formatted(keyword);

        mockMvc.perform(
                        post("/tools/{toolName}/call", "knowledge_search")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(toolRequest)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.data[*].document.id")
                        .value(not(hasItem(documentId))));


        mockMvc.perform(
                        post("/tools/{toolName}/call", "knowledge_search")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(toolRequest)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(true))
                .andExpect(jsonPath("$.data[*].document.id")
                        .value(not(hasItem(documentId))));

        mockMvc.perform(
                        post("/knowledge/batch")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "default_category": "GENERAL",
                                          "documents": [
                                            {
                                              "id": "%s",
                                              "title": "批量导入缓存测试",
                                              "content": "导入成功后必须清除知识搜索工具缓存。",
                                              "keywords": ["%s"]
                                            }
                                          ]
                                        }
                                        """.formatted(documentId, keyword))
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imported").value(1));

        mockMvc.perform(
                        post("/tools/{toolName}/call", "knowledge_search")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(toolRequest)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.data[*].document.id")
                        .value(hasItem(documentId)));
    }
}
