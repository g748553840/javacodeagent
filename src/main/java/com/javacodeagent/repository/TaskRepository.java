package com.javacodeagent.repository;

import com.javacodeagent.entity.Task;
import com.javacodeagent.core.enums.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskRepository extends JpaRepository<Task, String> {
    List<Task> findByStatusOrderByPriorityDesc(TaskStatus status);
    List<Task> findByConversationId(String conversationId);
}
