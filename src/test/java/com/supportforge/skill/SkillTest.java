package com.supportforge.skill;

import com.supportforge.agent.AgentType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillTest {

    @Test
    void shouldMatchKeywordAndAgentIgnoringEnglishCase() {
        Skill skill = new Skill(
                "退款规范",
                "账单处理规则",
                "退款前必须核验订单。",
                "billing/SKILL.md",
                List.of("退款", "REFUND"),
                List.of("BILLING"),
                true
        );

        assertTrue(skill.matches("I need a refund", AgentType.BILLING));
        assertTrue(skill.matches("我需要退款", AgentType.BILLING));
        assertFalse(skill.matches("我需要退款", AgentType.TECHNICAL));
        assertFalse(skill.matches("只是问候", AgentType.BILLING));
    }

    @Test
    void shouldNeverMatchDisabledSkill() {
        Skill skill = new Skill(
                "禁用规则",
                "",
                "这条规则不应被注入。",
                "disabled/SKILL.md",
                List.of(),
                List.of(),
                false
        );

        assertFalse(skill.matches("任意消息", AgentType.GENERAL));
    }

    @Test
    void shouldLimitPromptBlockLength() {
        Skill skill = new Skill(
                "长度测试",
                "",
                "A".repeat(200),
                "length/SKILL.md",
                List.of(),
                List.of(),
                true
        );

        String block = skill.promptBlock(80);

        assertTrue(block.length() <= 80);
        assertTrue(block.endsWith("..."));
    }
}
