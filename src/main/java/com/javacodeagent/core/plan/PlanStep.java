package com.javacodeagent.core.plan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanStep {
    private int order;
    private String description;
    private String action;
    private Map<String, Object> parameters;

    @Builder.Default
    private StepStatus status = StepStatus.PENDING;

    /** 步骤开始执行时间 */
    private LocalDateTime startedAt;

    /** 步骤完成/失败时间 */
    private LocalDateTime finishedAt;

    /** 执行结果摘要（成功时为输出摘要，失败时为错误信息） */
    private String result;

    public enum StepStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }
}
