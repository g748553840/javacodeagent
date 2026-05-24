package com.javacodeagent.core.plan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanResult {
    private Plan plan;
    private String output;
    private boolean success;
    private String error;

    public static PlanResult success(Plan plan, String output) {
        return PlanResult.builder().success(true).plan(plan).output(output).build();
    }

    public static PlanResult error(String error) {
        return PlanResult.builder().success(false).error(error).build();
    }
}
