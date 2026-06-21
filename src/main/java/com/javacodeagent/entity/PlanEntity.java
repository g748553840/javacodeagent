package com.javacodeagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * JPA 实体：持久化计划（Plan）。
 *
 * <p>步骤列表（List&lt;PlanStep&gt;）序列化为 JSON CLOB 存储，避免额外的子表和关联查询开销，
 * 与 TaskManager 的依赖关联方式不同（Plan 的 steps 天然有序、不需要跨 Plan 引用）。
 *
 * <p>allowedPrompts 也序列化为 JSON 字符串。
 */
@Data
@Entity
@Table(name = "plans")
public class PlanEntity {

    @Id
    private String id;

    private String conversationId;

    @Column(columnDefinition = "CLOB")
    private String description;

    /** Plan.PlanMode 枚举名 */
    private String mode;

    /** Plan.PlanStatus 枚举名 */
    private String status;

    /**
     * 序列化为 JSON 的 List&lt;PlanStep&gt;，包含每步的 order/description/action/status/
     * startedAt/finishedAt/result/parameters。
     */
    @Column(columnDefinition = "CLOB")
    private String stepsJson;

    /**
     * 序列化为 JSON 的 List&lt;String&gt;，批准后允许的操作列表。
     */
    @Column(columnDefinition = "CLOB")
    private String allowedPromptsJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
