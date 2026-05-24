package com.javacodeagent.core.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillResult {
    private String output;
    private boolean success;
    private String error;
    private List<String> actions;
    private Map<String, Object> data;

    public static SkillResult success(String output) {
        return SkillResult.builder().success(true).output(output).build();
    }

    public static SkillResult error(String error) {
        return SkillResult.builder().success(false).error(error).build();
    }
}
