package com.javacodeagent.entity;

import com.javacodeagent.core.enums.TaskStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Set;

@Data
@Entity
@Table(name = "tasks")
public class Task {

    @Id
    private String id;

    private String conversationId;

    private String subject;

    @Column(columnDefinition = "CLOB")
    private String description;

    private String activeForm;

    @Enumerated(EnumType.STRING)
    private TaskStatus status;

    private String owner;

    private int priority;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "task_dependencies",
        joinColumns = @JoinColumn(name = "task_id"),
        inverseJoinColumns = @JoinColumn(name = "depends_on_task_id"))
    private Set<Task> blockedBy;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = TaskStatus.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
