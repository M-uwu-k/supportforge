package com.supportforge.agent;

import com.supportforge.intent.IntentCategory;
import com.supportforge.intent.UrgencyLevel;
import com.supportforge.knowledge.KnowledgeBase;
import com.supportforge.knowledge.KnowledgeCategory;
import com.supportforge.knowledge.KnowledgeContextBuilder;
import com.supportforge.knowledge.KnowledgeDocument;
import com.supportforge.knowledge.KnowledgeSearchResult;
import com.supportforge.llm.LlmClient;
import com.supportforge.llm.LlmRequest;
import com.supportforge.llm.LlmResponse;
import com.supportforge.monitor.PerformanceMonitor;
import com.supportforge.skill.SkillManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillPromptInjectionTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldInjectOnlySkillMatchingSpecialistAgent() throws IOException {
        writeSkill(
                "billing",
                "账单专用规则",
                "退款",
                "billing",
                true,
                "BILLING_ONLY_RULE"
        );
        writeSkill(
                "technical",
                "技术专用规则",
                "退款,报错",
                "technical",
                true,
                "TECHNICAL_ONLY_RULE"
        );
        writeSkill(
                "disabled",
                "已禁用规则",
                "退款",
                "billing",
                false,
                "DISABLED_RULE"
        );

        SkillManager skillManager = manager();
        CapturingLlmClient llmClient = new CapturingLlmClient();
        AgentLlmEnhancer enhancer = enhancer(llmClient, skillManager);

        enhancer.enhance(
                request("我要申请退款", IntentCategory.REFUND),
                AgentType.BILLING,
                KnowledgeCategory.BILLING,
                "账单专业要求",
                "退款需要人工审核。"
        );

        String systemPrompt = llmClient.lastRequest().systemPrompt();
        assertTrue(systemPrompt.contains("BILLING_ONLY_RULE"));
        assertFalse(systemPrompt.contains("TECHNICAL_ONLY_RULE"));
        assertFalse(systemPrompt.contains("DISABLED_RULE"));
    }

    @Test
    void shouldUseReloadedSkillOnNextSpecialistRequest() throws IOException {
        Path file = writeSkill(
                "billing",
                "账单热加载规则",
                "退款",
                "billing",
                true,
                "RULE_VERSION_ONE"
        );

        SkillManager skillManager = manager();
        CapturingLlmClient llmClient = new CapturingLlmClient();
        AgentLlmEnhancer enhancer = enhancer(llmClient, skillManager);
        AgentRequest request = request("退款", IntentCategory.REFUND);

        enhancer.enhance(
                request,
                AgentType.BILLING,
                KnowledgeCategory.BILLING,
                "账单专业要求",
                "退款需要审核。"
        );
        assertTrue(
                llmClient.lastRequest()
                        .systemPrompt()
                        .contains("RULE_VERSION_ONE")
        );

        writeFile(
                file,
                skillText(
                        "账单热加载规则",
                        "退款",
                        "billing",
                        true,
                        "RULE_VERSION_TWO"
                )
        );
        skillManager.reload();

        enhancer.enhance(
                request,
                AgentType.BILLING,
                KnowledgeCategory.BILLING,
                "账单专业要求",
                "退款需要审核。"
        );

        String reloadedPrompt = llmClient.lastRequest().systemPrompt();
        assertTrue(reloadedPrompt.contains("RULE_VERSION_TWO"));
        assertFalse(reloadedPrompt.contains("RULE_VERSION_ONE"));
    }

    @Test
    void shouldInjectGeneralSkillIntoGeneralLlmRequest() throws IOException {
        writeSkill(
                "general",
                "通用专用规则",
                "帮助",
                "general",
                true,
                "GENERAL_ONLY_RULE"
        );

        SkillManager skillManager = manager();
        CapturingLlmClient llmClient = new CapturingLlmClient();
        PerformanceMonitor monitor = monitor();
        GeneralAgent generalAgent = new GeneralAgent(
                llmClient,
                monitor,
                skillManager
        );

        AgentResponse response = generalAgent.handle(
                request("请帮助我处理这个问题", IntentCategory.QUERY)
        );

        assertTrue(response.success());
        assertTrue(
                llmClient.lastRequest()
                        .systemPrompt()
                        .contains("GENERAL_ONLY_RULE")
        );
    }

    private AgentLlmEnhancer enhancer(
            LlmClient llmClient,
            SkillManager skillManager
    ) {
        KnowledgeContextBuilder contextBuilder =
                new KnowledgeContextBuilder(
                        new EmptyKnowledgeBase()
                );

        return new AgentLlmEnhancer(
                llmClient,
                contextBuilder,
                monitor(),
                skillManager,
                true
        );
    }

    private PerformanceMonitor monitor() {
        return new PerformanceMonitor(
                new SimpleMeterRegistry()
        );
    }

    private SkillManager manager() {
        SkillManager manager = new SkillManager(
                tempDir.toString(),
                5000
        );
        manager.reload();
        return manager;
    }

    private AgentRequest request(
            String message,
            IntentCategory intent
    ) {
        return new AgentRequest(
                message,
                "skill-user",
                "skill-conversation",
                "",
                Map.of(),
                intent,
                intent.name().toLowerCase(),
                UrgencyLevel.LOW,
                0.95
        );
    }

    private Path writeSkill(
            String directory,
            String name,
            String keywords,
            String agents,
            boolean enabled,
            String content
    ) throws IOException {
        Path file = tempDir.resolve(
                directory + "/SKILL.md"
        );
        writeFile(
                file,
                skillText(
                        name,
                        keywords,
                        agents,
                        enabled,
                        content
                )
        );
        return file;
    }

    private String skillText(
            String name,
            String keywords,
            String agents,
            boolean enabled,
            String content
    ) {
        return """
                ---
                name: %s
                keywords: %s
                agents: %s
                enabled: %s
                ---

                # %s

                %s
                """.formatted(
                name,
                keywords,
                agents,
                enabled,
                name,
                content
        );
    }

    private void writeFile(
            Path file,
            String content
    ) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                content,
                StandardCharsets.UTF_8
        );
    }

    private static final class CapturingLlmClient
            implements LlmClient {

        private final List<LlmRequest> requests =
                new ArrayList<>();

        @Override
        public LlmResponse generate(LlmRequest request) {
            requests.add(request);

            return new LlmResponse(
                    "测试模型回答",
                    "capturing-test-model",
                    10,
                    5,
                    1.0,
                    true
            );
        }

        private LlmRequest lastRequest() {
            return requests.getLast();
        }
    }

    private static final class EmptyKnowledgeBase
            implements KnowledgeBase {

        @Override
        public List<KnowledgeDocument> readableDocuments() { return List.of(); }

        @Override
        public List<KnowledgeSearchResult> search(
                String query,
                KnowledgeCategory category,
                int limit
        ) {
            return List.of();
        }

        @Override
        public KnowledgeDocument add(
                KnowledgeDocument document
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KnowledgeDocument delete(
                String documentId
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int count() {
            return 0;
        }
    }
}
