package com.javacodeagent.core.skill;

import com.javacodeagent.core.model.ExecutionContext;

import java.util.Map;

public interface Skill {
    String getName();

    String getDescription();

    Map<String, Object> getParameterSchema();

    SkillResult execute(SkillInput input, ExecutionContext context);
}
