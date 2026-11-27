package com.supportforge.api;

import com.supportforge.skill.SkillManager;
import com.supportforge.skill.SkillStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/skills")
public class SkillController {

    private final SkillManager skillManager;

    public SkillController(
            SkillManager skillManager
    ) {
        this.skillManager = skillManager;
    }

    @GetMapping
    public SkillStatus status() {
        return skillManager.status();
    }

    @PostMapping("/reload")
    public SkillStatus reload() {
        skillManager.reload();
        return skillManager.status();
    }
}