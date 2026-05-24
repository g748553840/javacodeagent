package com.javacodeagent.core.plan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 计划 - 支持 EXPLORE → DRAFT → APPROVED 流程
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Plan {
    private String id;
    private String conversationId;
    private String description;

    @Builder.Default
    private PlanMode mode = PlanMode.DRAFT;     // 当前模式

    @Builder.Default
    private PlanStatus status = PlanStatus.DRAFT; // 当前状态

    @Builder.Default
    private List<PlanStep> steps = new ArrayList<>();
    private List<String> allowedPrompts;          // 批准后允许的操作列表
    private Map<String, Object> metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum PlanMode {
        EXPLORE,  // 探索模式（只读）
        DRAFT,    // 起草模式
        APPROVED  // 已批准
    }

    public enum PlanStatus {
        DRAFT,      // 起草中
        IN_REVIEW,  // 审阅中
        APPROVED,   // 已批准
        IN_PROGRESS,// 执行中
        COMPLETED,  // 已完成
        FAILED,     // 失败
        REJECTED    // 已拒绝
    }
}
