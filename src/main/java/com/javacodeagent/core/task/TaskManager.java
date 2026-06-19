package com.javacodeagent.core.task;

import com.javacodeagent.core.enums.TaskStatus;
import com.javacodeagent.entity.Task;
import com.javacodeagent.repository.TaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 任务管理器。
 *
 * 双层存储：
 *  - 内存 ConcurrentHashMap（TaskRecord）：快速读取，记录 blocks/blockedBy 反向引用
 *  - JPA TaskRepository（Task）：持久化到 H2/PostgreSQL，重启后数据不丢失
 *
 * 所有写操作（create / updateStatus / delete）同时更新内存和数据库。
 * 启动时从数据库加载已有任务恢复内存状态。
 */
@Slf4j
@Service
public class TaskManager {

    private final Map<String, TaskRecord> tasks = new ConcurrentHashMap<>();
    private final TaskRepository taskRepository;

    public TaskManager(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
        loadFromDatabase();
    }

    // -------------------------------------------------------------------------
    // 创建
    // -------------------------------------------------------------------------

    public TaskRecord createTask(String subject, String description) {
        return createTask(subject, description, null, null);
    }

    public TaskRecord createTask(String subject, String description,
                                 String activeForm, Set<String> blockedBy) {
        String id = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();

        Set<String> deps = blockedBy != null ? new HashSet<>(blockedBy) : new HashSet<>();

        TaskRecord record = TaskRecord.builder()
            .id(id)
            .subject(subject)
            .description(description)
            .activeForm(activeForm)
            .status(TaskStatus.PENDING)
            .blockedBy(deps)
            .blocks(new HashSet<>())
            .createdAt(now)
            .updatedAt(now)
            .build();

        tasks.put(id, record);

        // 维护反向引用
        for (String depId : deps) {
            TaskRecord dep = tasks.get(depId);
            if (dep != null) dep.getBlocks().add(id);
        }

        // 持久化到数据库
        persistToDatabase(record);

        log.info("Created task {} - {} (blockedBy: {})", id, subject, deps);
        return record;
    }

    // -------------------------------------------------------------------------
    // 查询
    // -------------------------------------------------------------------------

    public TaskRecord getTask(String taskId) {
        return tasks.get(taskId);
    }

    public List<TaskRecord> listTasks() {
        return List.copyOf(tasks.values());
    }

    public List<TaskRecord> listTasksByStatus(TaskStatus status) {
        return tasks.values().stream()
            .filter(t -> t.getStatus() == status)
            .collect(Collectors.toList());
    }

    public boolean canStart(String taskId) {
        TaskRecord task = tasks.get(taskId);
        if (task == null) return false;
        return task.getBlockedBy().stream().allMatch(depId -> {
            TaskRecord dep = tasks.get(depId);
            return dep != null && dep.getStatus() == TaskStatus.COMPLETED;
        });
    }

    public List<TaskRecord> getBlockingTasks(String taskId) {
        TaskRecord task = tasks.get(taskId);
        if (task == null) return List.of();
        return task.getBlockedBy().stream()
            .map(tasks::get)
            .filter(t -> t != null && t.getStatus() != TaskStatus.COMPLETED)
            .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // 更新
    // -------------------------------------------------------------------------

    public TaskRecord updateStatus(String taskId, TaskStatus status) {
        TaskRecord record = tasks.get(taskId);
        if (record == null) {
            log.warn("Task not found: {}", taskId);
            return null;
        }

        record.setStatus(status);
        record.setUpdatedAt(LocalDateTime.now());
        if (status == TaskStatus.COMPLETED) {
            record.setCompletedAt(LocalDateTime.now());
        }

        // 同步到数据库
        taskRepository.findById(taskId).ifPresent(entity -> {
            entity.setStatus(status);
            entity.setUpdatedAt(record.getUpdatedAt());
            entity.setCompletedAt(record.getCompletedAt());
            taskRepository.save(entity);
        });

        log.info("Task {} status → {}", taskId, status);
        return record;
    }

    public TaskRecord updateTaskDetails(String taskId, String owner, String subject) {
        TaskRecord record = tasks.get(taskId);
        if (record == null) {
            log.warn("Task not found: {}", taskId);
            return null;
        }

        if (owner != null) record.setOwner(owner);
        if (subject != null) record.setSubject(subject);
        record.setUpdatedAt(LocalDateTime.now());

        taskRepository.findById(taskId).ifPresent(entity -> {
            if (owner != null) entity.setOwner(owner);
            if (subject != null) entity.setSubject(subject);
            entity.setUpdatedAt(record.getUpdatedAt());
            taskRepository.save(entity);
        });

        log.info("Task {} details updated (owner={}, subject={})", taskId, owner, subject);
        return record;
    }

    public TaskRecord stopTask(String taskId) {
        return updateStatus(taskId, TaskStatus.FAILED);
    }

    // -------------------------------------------------------------------------
    // 删除
    // -------------------------------------------------------------------------

    public void deleteTask(String taskId) {
        TaskRecord record = tasks.remove(taskId);
        if (record != null) {
            // 清理反向引用
            for (String depId : record.getBlockedBy()) {
                TaskRecord dep = tasks.get(depId);
                if (dep != null && dep.getBlocks() != null) dep.getBlocks().remove(taskId);
            }
            for (String blockedId : record.getBlocks()) {
                TaskRecord blocked = tasks.get(blockedId);
                if (blocked != null) blocked.getBlockedBy().remove(taskId);
            }
            taskRepository.deleteById(taskId);
            log.info("Deleted task {}", taskId);
        }
    }

    // -------------------------------------------------------------------------
    // 持久化辅助
    // -------------------------------------------------------------------------

    private void persistToDatabase(TaskRecord record) {
        try {
            Task entity = new Task();
            entity.setId(record.getId());
            entity.setSubject(record.getSubject());
            entity.setDescription(record.getDescription());
            entity.setActiveForm(record.getActiveForm());
            entity.setStatus(record.getStatus());
            entity.setOwner(record.getOwner());
            entity.setCreatedAt(record.getCreatedAt());
            entity.setUpdatedAt(record.getUpdatedAt());

            // 保存 blockedBy 关联（先确保依赖任务已存在于 DB）
            if (record.getBlockedBy() != null && !record.getBlockedBy().isEmpty()) {
                java.util.Set<Task> depEntities = new java.util.HashSet<>();
                for (String depId : record.getBlockedBy()) {
                    taskRepository.findById(depId).ifPresent(depEntities::add);
                }
                entity.setBlockedBy(depEntities);
            } else {
                entity.setBlockedBy(new java.util.HashSet<>());
            }

            taskRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to persist task {} to database: {}", record.getId(), e.getMessage());
        }
    }

    /**
     * 应用启动时从数据库恢复任务到内存 Map。
     */
    private void loadFromDatabase() {
        try {
            List<Task> dbTasks = taskRepository.findAll();
            for (Task entity : dbTasks) {
                // 从 @ManyToMany(fetch=EAGER) 恢复 blockedBy ID 集合
                Set<String> blockedByIds = new HashSet<>();
                if (entity.getBlockedBy() != null) {
                    for (Task dep : entity.getBlockedBy()) {
                        blockedByIds.add(dep.getId());
                    }
                }

                TaskRecord record = TaskRecord.builder()
                    .id(entity.getId())
                    .subject(entity.getSubject())
                    .description(entity.getDescription())
                    .activeForm(entity.getActiveForm())
                    .status(entity.getStatus() != null ? entity.getStatus() : TaskStatus.PENDING)
                    .owner(entity.getOwner())
                    .blockedBy(blockedByIds)
                    .blocks(new HashSet<>())
                    .createdAt(entity.getCreatedAt())
                    .updatedAt(entity.getUpdatedAt())
                    .completedAt(entity.getCompletedAt())
                    .build();
                tasks.put(record.getId(), record);
            }

            // 所有任务加载后，重建反向 blocks 引用
            for (TaskRecord record : tasks.values()) {
                for (String depId : record.getBlockedBy()) {
                    TaskRecord dep = tasks.get(depId);
                    if (dep != null) dep.getBlocks().add(record.getId());
                }
            }

            if (!dbTasks.isEmpty()) {
                log.info("Loaded {} tasks from database (dependencies restored)", dbTasks.size());
            }
        } catch (Exception e) {
            log.warn("Could not load tasks from database (first start?): {}", e.getMessage());
        }
    }
}
