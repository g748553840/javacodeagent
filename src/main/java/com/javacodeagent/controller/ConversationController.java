package com.javacodeagent.controller;

import com.javacodeagent.core.conversation.ConversationManager;
import com.javacodeagent.core.conversation.ConversationRequest;
import com.javacodeagent.core.conversation.ConversationResponse;
import com.javacodeagent.core.plan.PlanResult;
import com.javacodeagent.core.plan.PlanService;
import com.javacodeagent.core.task.TaskManager;
import com.javacodeagent.core.task.TaskRecord;
import com.javacodeagent.core.enums.TaskStatus;
import com.javacodeagent.core.memory.MemoryEntry;
import com.javacodeagent.core.memory.MemoryService;
import com.javacodeagent.entity.Conversation;
import com.javacodeagent.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationManager conversationManager;
    private final TaskManager taskManager;
    private final PlanService planService;
    private final MemoryService memoryService;
    private final ConversationRepository conversationRepository;

    // ===== 对话 =====

    @PostMapping("/chat")
    public Mono<ConversationResponse> chat(@RequestBody ConversationRequest request) {
        return conversationManager.processMessage(request);
    }

    @GetMapping("/health")
    public Mono<String> health() {
        return Mono.just("OK");
    }

    // ===== 会话管理 =====

    @PostMapping("/conversations")
    public ResponseEntity<Conversation> createConversation(@RequestBody Conversation conversation) {
        Conversation saved = conversationRepository.save(conversation);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/conversations")
    public ResponseEntity<List<Conversation>> listConversations(
            @RequestParam(required = false) String userId) {
        if (userId != null) {
            return ResponseEntity.ok(conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId));
        }
        return ResponseEntity.ok(conversationRepository.findAll());
    }

    // ===== 任务 =====

    @PostMapping("/tasks")
    public ResponseEntity<TaskRecord> createTask(@RequestBody Map<String, String> body) {
        TaskRecord task = taskManager.createTask(
            body.get("subject"),
            body.get("description")
        );
        return ResponseEntity.ok(task);
    }

    @GetMapping("/tasks")
    public ResponseEntity<List<TaskRecord>> listTasks() {
        return ResponseEntity.ok(taskManager.listTasks());
    }

    @GetMapping("/tasks/{id}")
    public ResponseEntity<TaskRecord> getTask(@PathVariable String id) {
        TaskRecord task = taskManager.getTask(id);
        return task != null ? ResponseEntity.ok(task) : ResponseEntity.notFound().build();
    }

    @PutMapping("/tasks/{id}/status")
    public ResponseEntity<TaskRecord> updateTaskStatus(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        try {
            TaskStatus status = TaskStatus.valueOf(body.get("status").toUpperCase());
            TaskRecord task = taskManager.updateStatus(id, status);
            return task != null ? ResponseEntity.ok(task) : ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    // ===== 计划 =====

    @PostMapping("/plan")
    public ResponseEntity<PlanResult> createPlan(@RequestBody Map<String, String> body) {
        PlanResult result = planService.enterPlanMode(
            body.get("conversationId"),
            body.get("description")
        );
        return ResponseEntity.ok(result);
    }

    @GetMapping("/plan/{id}")
    public ResponseEntity<?> getPlan(@PathVariable String id) {
        var plan = planService.getPlan(id);
        return plan != null ? ResponseEntity.ok(plan) : ResponseEntity.notFound().build();
    }

    @PostMapping("/plan/{id}/approve")
    public ResponseEntity<PlanResult> approvePlan(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, List<String>> body) {
        List<String> allowedPrompts = body != null ? body.get("allowedPrompts") : null;
        PlanResult result = planService.approvePlan(id, allowedPrompts);
        return result.isSuccess() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @PostMapping("/plan/{id}/reject")
    public ResponseEntity<PlanResult> rejectPlan(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        PlanResult result = planService.rejectPlan(id, body.getOrDefault("reason", "No reason provided"));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/plan/{id}/execute")
    public ResponseEntity<PlanResult> executePlan(@PathVariable String id) {
        PlanResult result = planService.executePlan(id);
        return result.isSuccess() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    // ===== 记忆 =====

    @PostMapping("/memory")
    public ResponseEntity<String> saveMemory(@RequestBody MemoryEntry entry) {
        memoryService.saveMemory(entry);
        return ResponseEntity.ok("Memory saved");
    }

    @GetMapping("/memory/{userId}")
    public ResponseEntity<List<MemoryEntry>> getMemories(@PathVariable String userId) {
        return ResponseEntity.ok(memoryService.getUserMemories(userId));
    }

    @GetMapping("/memory/{userId}/search")
    public ResponseEntity<List<MemoryEntry>> searchMemories(
            @PathVariable String userId,
            @RequestParam String keyword) {
        return ResponseEntity.ok(memoryService.searchMemories(userId, keyword));
    }

    @DeleteMapping("/memory/{userId}/{memoryId}")
    public ResponseEntity<String> deleteMemory(
            @PathVariable String userId,
            @PathVariable String memoryId) {
        memoryService.deleteMemory(userId, memoryId);
        return ResponseEntity.ok("Memory deleted");
    }
}
