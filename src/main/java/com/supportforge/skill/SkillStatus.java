package com.supportforge.skill;

import java.util.List;

public record SkillStatus(
        String rootDir,
        int count,
        List<SkillSummary> skills,
        List<String> errors
) {
}