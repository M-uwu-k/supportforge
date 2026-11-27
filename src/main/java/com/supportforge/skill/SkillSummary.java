package com.supportforge.skill;

import java.util.List;


public record SkillSummary(
        String name,
        String description,
        String path,
        List<String> keywords,
        List<String> agents,
        boolean enabled,
        int contentChars
) {


    public static SkillSummary from(Skill skill) {

        return new SkillSummary(
                skill.name(),
                skill.description(),
                skill.path(),
                skill.keywords(),
                skill.agents(),
                skill.enabled(),
                skill.content().length()
        );


    }
}
