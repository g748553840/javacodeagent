package com.javacodeagent.core.skill;

import com.javacodeagent.core.model.ExecutionContext;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class SkillManager {

    private final Map<String, Skill> skills = new ConcurrentHashMap<>();
    /** Spring 自动注入所有实现了 Skill 接口的 @Component Bean */
    private final List<Skill> skillBeans;

    @PostConstruct
    public void init() {
        for (Skill skill : skillBeans) {
            registerSkill(skill);
        }
    }

    public void registerSkill(Skill skill) {
        skills.put(skill.getName(), skill);
        log.info("Registered skill: {}", skill.getName());
    }

    public SkillResult executeSkill(String skillName, SkillInput input, ExecutionContext context) {
        Skill skill = skills.get(skillName);
        if (skill == null) {
            return SkillResult.error("Unknown skill: " + skillName);
        }

        try {
            log.info("Executing skill: {}", skillName);
            return skill.execute(input, context);
        } catch (Exception e) {
            log.error("Skill execution failed: {}", skillName, e);
            return SkillResult.error("Skill execution failed: " + e.getMessage());
        }
    }
}
