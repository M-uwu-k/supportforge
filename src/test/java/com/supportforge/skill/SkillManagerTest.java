package com.supportforge.skill;

import com.supportforge.agent.AgentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadFrontMatterAndBuildMatchingPrompt() throws IOException {
        Path skillFile = tempDir.resolve("billing/SKILL.md");
        write(skillFile, """
                ---
                name: 退款处理规则
                description: 账单退款操作边界
                keywords: 退款,refund
                agents: billing
                enabled: true
                ---

                # 退款处理规则

                退款前必须核验订单和支付记录。
                """);

        SkillManager manager = new SkillManager(
                tempDir.toString(),
                5000
        );

        manager.reload();

        assertEquals(1, manager.skills().size());
        Skill skill = manager.skills().getFirst();
        assertEquals("退款处理规则", skill.name());
        assertEquals("账单退款操作边界", skill.description());
        assertEquals(2, skill.keywords().size());
        assertEquals("billing", skill.agents().getFirst());
        assertFalse(skill.content().startsWith("# 退款处理规则"));

        String billingPrompt = manager.promptFor(
                "我要申请退款",
                AgentType.BILLING
        );

        assertTrue(billingPrompt.contains("退款处理规则"));
        assertTrue(billingPrompt.contains("退款前必须核验订单和支付记录"));
        assertTrue(
                manager.promptFor(
                        "我要申请退款",
                        AgentType.TECHNICAL
                ).isEmpty()
        );
    }

    @Test
    void shouldReloadChangedSkillWithoutCreatingNewManager() throws IOException {
        Path skillFile = tempDir.resolve("general/SKILL.md");
        write(skillFile, skillText("第一版规则"));

        SkillManager manager = new SkillManager(
                tempDir.toString(),
                5000
        );
        manager.reload();

        assertTrue(
                manager.promptFor("帮助", AgentType.GENERAL)
                        .contains("第一版规则")
        );

        write(skillFile, skillText("第二版规则"));
        manager.reload();

        String prompt = manager.promptFor(
                "帮助",
                AgentType.GENERAL
        );
        assertTrue(prompt.contains("第二版规则"));
        assertFalse(prompt.contains("第一版规则"));
    }

    @Test
    void shouldKeepValidSkillsWhenAnotherFileIsInvalid() throws IOException {
        write(
                tempDir.resolve("valid/SKILL.md"),
                skillText("有效规则")
        );
        write(
                tempDir.resolve("invalid/SKILL.md"),
                """
                        ---
                        name:
                        enabled: true
                        ---

                        这篇文档的名称为空，应记录加载错误。
                        """
        );

        SkillManager manager = new SkillManager(
                tempDir.toString(),
                5000
        );
        manager.reload();

        assertEquals(1, manager.skills().size());
        assertEquals(1, manager.errors().size());
        assertTrue(manager.errors().getFirst().contains("Skill名称不能为空"));
    }

    private String skillText(String rule) {
        return """
                ---
                name: 通用客服规则
                keywords: 帮助
                agents: general
                enabled: true
                ---

                # 通用客服规则

                %s
                """.formatted(rule);
    }

    private void write(
            Path path,
            String content
    ) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(
                path,
                content,
                StandardCharsets.UTF_8
        );
    }
}
