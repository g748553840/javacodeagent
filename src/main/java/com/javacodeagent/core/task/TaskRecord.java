package com.javacodeagent.core.task;

import com.javacodeagent.core.enums.TaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * 任务记录 - 跟踪工作进度和依赖关系
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskRecord {
    private String id;
    private String conversationId;
    private String subject;
    private String description;
    private String activeForm;          // 执行中显示的动态形式
    private TaskStatus status;
    private String owner;

    @Builder.Default
    private Set<String> blockedBy = new HashSet<>();  // 依赖的任务ID

    @Builder.Default
    private Set<String> blocks = new HashSet<>();     // 被阻塞的任务ID

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}
