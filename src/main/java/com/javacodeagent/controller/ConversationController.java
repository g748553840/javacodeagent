package com.javacodeagent.controller;

import com.javacodeagent.core.plan.PlanResult;
import com.javacodeagent.core.plan.PlanService;
import com.javacodeagent.core.task.TaskManager;
import com.javacodeagent.core.task.TaskRecord;
import com.javacodeagent.core.enums.TaskStatus;
import com.javacodeagent.core.memory.MemoryEntry;
import com.javacodeagent.core.memory.MemoryService;
import com.javacodeagent.core.skill.SkillInput;
import com.javacodeagent.core.skill.SkillManager;
import com.javacodeagent.core.skill.SkillResult;
import com.javacodeagent.entity.Conversation;
import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ConversationController {

    private final TaskManager taskManager;
    private final PlanService planService;
    private final MemoryService memoryService;
    private final SkillManager skillManager;
    private final ConversationRepository conversationRepository;

    // -------------------------------------------------------------------------
    // 通用辅助：从请求头提取 userId
    // -------------------------------------------------------------------------

    private String extractUserId(ServerWebExchange exchange) {
        String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
        return (userId != null && !userId.isBlank()) ? userId.trim() : "default";
    }

    // ===== 健康检查 =====

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

    @GetMapping("/conversations/{id}")
    public ResponseEntity<Conversation> getConversation(@PathVariable String id) {
        return conversationRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    // ===== 任务 =====

    @PostMapping("/tasks")
    public ResponseEntity<TaskRecord> createTask(@RequestBody Map<String, Object> body) {
        String subject = (String) body.get("subject");
        String description = (String) body.get("description");
        String activeForm = (String) body.get("activeForm");
        Set<String> blockedBy = body.get("blockedBy") instanceof List<?> list
            ? new HashSet<>(list.stream().map(Object::toString).toList())
            : new HashSet<>();
        TaskRecord task = taskManager.createTask(subject, description, activeForm, blockedBy);
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

    @PutMapping("/tasks/{id}")
    public ResponseEntity<TaskRecord> updateTask(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        TaskRecord task = taskManager.getTask(id);
        if (task == null) return ResponseEntity.notFound().build();

        if (body.containsKey("status")) {
            try {
                TaskStatus status = TaskStatus.valueOf(body.get("status").toUpperCase());
                taskManager.updateStatus(id, status);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        }
        String newOwner = body.containsKey("owner") ? body.get("owner") : null;
        String newSubject = body.containsKey("subject") ? body.get("subject") : null;
        if (newOwner != null || newSubject != null) {
            taskManager.updateTaskDetails(id, newOwner, newSubject);
        }
        return ResponseEntity.ok(taskManager.getTask(id));
    }

    @DeleteMapping("/tasks/{id}")
    public ResponseEntity<Void> deleteTask(@PathVariable String id) {
        TaskRecord task = taskManager.getTask(id);
        if (task == null) return ResponseEntity.notFound().build();
        taskManager.deleteTask(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/tasks/{id}/blocking")
    public ResponseEntity<List<TaskRecord>> getBlockingTasks(@PathVariable String id) {
        return ResponseEntity.ok(taskManager.getBlockingTasks(id));
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

    @PostMapping("/plan/{id}/submit")
    public ResponseEntity<PlanResult> submitPlan(@PathVariable String id) {
        PlanResult result = planService.submitForReview(id);
        return result.isSuccess() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @PostMapping("/plan/{id}/execute")
    public ResponseEntity<PlanResult> executePlan(@PathVariable String id) {
        PlanResult result = planService.executePlan(id);
        return result.isSuccess() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @PostMapping("/plan/{id}/next-step")
    public ResponseEntity<PlanResult> executeNextStep(@PathVariable String id) {
        PlanResult result = planService.executeNextStep(id);
        return result.isSuccess() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @PostMapping("/plan/{id}/complete-step")
    public ResponseEntity<PlanResult> completeCurrentStep(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> body) {
        String summary = body != null ? body.getOrDefault("result", "Completed") : "Completed";
        PlanResult result = planService.completeCurrentStep(id, summary);
        return result.isSuccess() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @PostMapping("/plan/{id}/fail-step")
    public ResponseEntity<PlanResult> failCurrentStep(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        PlanResult result = planService.failCurrentStep(id,
            body.getOrDefault("error", "Unknown error"));
        return result.isSuccess() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    // ===== 记忆 =====

    /**
     * 保存记忆。
     * userId 优先使用 entry.userId，若未填则从 X-User-Id 请求头提取。
     */
    @PostMapping("/memory")
    public ResponseEntity<String> saveMemory(
            @RequestBody MemoryEntry entry,
            ServerWebExchange exchange) {
        if (entry.getUserId() == null || entry.getUserId().isBlank()) {
            entry.setUserId(extractUserId(exchange));
        }
        memoryService.saveMemory(entry);
        return ResponseEntity.ok("Memory saved");
    }

    /**
     * 查询用户记忆。
     * 路径 userId 保持向后兼容；X-User-Id 头优先（防止越权访问其他用户数据）。
     */
    @GetMapping("/memory/{userId}")
    public ResponseEntity<List<MemoryEntry>> getMemories(
            @PathVariable String userId,
            ServerWebExchange exchange) {
        String effectiveUserId = resolveUserId(userId, exchange);
        return ResponseEntity.ok(memoryService.getUserMemories(effectiveUserId));
    }

    @GetMapping("/memory/{userId}/search")
    public ResponseEntity<List<MemoryEntry>> searchMemories(
            @PathVariable String userId,
            @RequestParam String keyword,
            ServerWebExchange exchange) {
        String effectiveUserId = resolveUserId(userId, exchange);
        return ResponseEntity.ok(memoryService.searchMemories(effectiveUserId, keyword));
    }

    @DeleteMapping("/memory/{userId}/{memoryId}")
    public ResponseEntity<String> deleteMemory(
            @PathVariable String userId,
            @PathVariable String memoryId,
            ServerWebExchange exchange) {
        String effectiveUserId = resolveUserId(userId, exchange);
        memoryService.deleteMemory(effectiveUserId, memoryId);
        return ResponseEntity.ok("Memory deleted");
    }

    /**
     * userId 解析策略：
     * 1. X-User-Id 请求头（接入层认证时已确认身份，优先）
     * 2. 路径参数（向后兼容）
     * 3. 退化为 "default"
     */
    private String resolveUserId(String pathUserId, ServerWebExchange exchange) {
        String headerUserId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
        if (headerUserId != null && !headerUserId.isBlank()) return headerUserId.trim();
        if (pathUserId != null && !pathUserId.isBlank()) return pathUserId.trim();
        return "default";
    }

    // ===== Skill（技能） =====

    /**
     * 执行指定 Skill。
     * Skill 是预定义的可复用能力单元，通过 {@link SkillManager} 注册后即可调用。
     * 请求体：{@code {"name": "skill-name", "parameters": {...}}}
     */
    @PostMapping("/skills/{name}")
    public ResponseEntity<SkillResult> executeSkill(
            @PathVariable String name,
            @RequestBody(required = false) Map<String, Object> parameters,
            ServerWebExchange exchange) {
        String userId = extractUserId(exchange);
        ExecutionContext ctx = ExecutionContext.builder()
            .userId(userId)
            .build();
        SkillInput input = new SkillInput(name, parameters != null ? parameters : Map.of());
        SkillResult result = skillManager.executeSkill(name, input, ctx);
        return result.isSuccess()
            ? ResponseEntity.ok(result)
            : ResponseEntity.badRequest().body(result);
    }
}
