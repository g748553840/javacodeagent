package com.javacodeagent.core.task;

import com.javacodeagent.core.enums.TaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 任务管理器 - 跟踪工作进度和依赖关系
 */
@Slf4j
@Service
public class TaskManager {

    private final Map<String, TaskRecord> tasks = new ConcurrentHashMap<>();

    /**
     * 创建任务
     */
    public TaskRecord createTask(String subject, String description) {
        TaskRecord task = TaskRecord.builder()
            .id(UUID.randomUUID().toString())
            .subject(subject)
            .description(description)
            .status(TaskStatus.PENDING)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

        tasks.put(task.getId(), task);
        log.info("Created task: {} - {}", task.getId(), subject);
        return task;
    }

    /**
     * 创建任务（带完整参数）
     */
    public TaskRecord createTask(String subject, String description, String activeForm,
                                  Set<String> blockedBy) {
        TaskRecord task = TaskRecord.builder()
            .id(UUID.randomUUID().toString())
            .subject(subject)
            .description(description)
            .activeForm(activeForm)
            .status(blockedBy != null && !blockedBy.isEmpty() ? TaskStatus.PENDING : TaskStatus.PENDING)
            .blockedBy(blockedBy != null ? blockedBy : Set.of())
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

        tasks.put(task.getId(), task);

        // 更新依赖关系中 blocks 反向引用
        if (task.getBlockedBy() != null) {
            for (String depId : task.getBlockedBy()) {
                TaskRecord dep = tasks.get(depId);
                if (dep != null) {
                    if (dep.getBlocks() == null) {
                        dep.setBlocks(new java.util.HashSet<>());
                    }
                    dep.getBlocks().add(task.getId());
                }
            }
        }

        log.info("Created task: {} - {} (blockedBy: {})", task.getId(), subject, blockedBy);
        return task;
    }

    /**
     * 更新任务状态
     */
    public TaskRecord updateStatus(String taskId, TaskStatus status) {
        TaskRecord task = tasks.get(taskId);
        if (task == null) {
            log.warn("Task not found: {}", taskId);
            return null;
        }

        task.setStatus(status);
        task.setUpdatedAt(LocalDateTime.now());

        if (status == TaskStatus.COMPLETED) {
            task.setCompletedAt(LocalDateTime.now());
        }

        log.info("Task {} status updated to: {}", taskId, status);
        return task;
    }

    /**
     * 获取任务
     */
    public TaskRecord getTask(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * 列出所有任务
     */
    public List<TaskRecord> listTasks() {
        return List.copyOf(tasks.values());
    }

    /**
     * 按状态列出任务
     */
    public List<TaskRecord> listTasksByStatus(TaskStatus status) {
        return tasks.values().stream()
            .filter(t -> t.getStatus() == status)
            .collect(Collectors.toList());
    }

    /**
     * 检查任务是否可以开始（所有依赖已完成）
     */
    public boolean canStart(String taskId) {
        TaskRecord task = tasks.get(taskId);
        if (task == null) return false;

        return task.getBlockedBy().stream()
            .allMatch(depId -> {
                TaskRecord dep = tasks.get(depId);
                return dep != null && dep.getStatus() == TaskStatus.COMPLETED;
            });
    }

    /**
     * 获取阻塞当前任务的任务列表
     */
    public List<TaskRecord> getBlockingTasks(String taskId) {
        TaskRecord task = tasks.get(taskId);
        if (task == null) return List.of();

        return task.getBlockedBy().stream()
            .map(tasks::get)
            .filter(t -> t != null && t.getStatus() != TaskStatus.COMPLETED)
            .collect(Collectors.toList());
    }

    /**
     * 停止任务（标记为失败）
     */
    public TaskRecord stopTask(String taskId) {
        return updateStatus(taskId, TaskStatus.FAILED);
    }

    /**
     * 删除任务
     */
    public void deleteTask(String taskId) {
        TaskRecord task = tasks.remove(taskId);
        if (task != null) {
            // 清理依赖引用
            if (task.getBlockedBy() != null) {
                for (String depId : task.getBlockedBy()) {
                    TaskRecord dep = tasks.get(depId);
                    if (dep != null && dep.getBlocks() != null) {
                        dep.getBlocks().remove(taskId);
                    }
                }
            }
            log.info("Deleted task: {}", taskId);
        }
    }
}
