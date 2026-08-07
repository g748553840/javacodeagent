package com.javacodeagent.controller;

import com.javacodeagent.core.plan.PlanResult;
import com.javacodeagent.core.plan.PlanService;
import com.javacodeagent.core.task.TaskManager;
import com.javacodeagent.core.task.TaskRecord;
import com.javacodeagent.core.enums.TaskStatus;
import com.javacodeagent.core.memory.MemoryEntry;
import com.javacodeagent.core.memory.MemoryService;
import com.javacodeagent.entity.Conversation;
import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.repository.ConversationRepository;
import com.javacodeagent.config.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
    private final ConversationRepository conversationRepository;

    // -------------------------------------------------------------------------
    // 通用辅助：从请求中提取 userId（JWT attribute 优先，兼容 X-User-Id 头）
    // -------------------------------------------------------------------------

    private String extractUserId(ServerWebExchange exchange) {
        // JWT 认证成功时，JwtAuthFilter 已将 userId 写入 exchange attribute
        String fromJwt = exchange.getAttribute(JwtAuthFilter.USER_ID_ATTR);
        if (fromJwt != null && !fromJwt.isBlank()) return fromJwt.trim();
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
    public Mono<ResponseEntity<Conversation>> createConversation(@RequestBody Conversation conversation) {
        return Mono.fromCallable(() -> conversationRepository.save(conversation))
            .subscribeOn(Schedulers.boundedElastic())
            .map(ResponseEntity::ok);
    }

    @GetMapping("/conversations")
    public Mono<ResponseEntity<List<Conversation>>> listConversations(
            @RequestParam(required = false) String userId) {
        return Mono.fromCallable(() -> userId != null
                ? conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId)
                : conversationRepository.findAll())
            .subscribeOn(Schedulers.boundedElastic())
            .map(ResponseEntity::ok);
    }

    @GetMapping("/conversations/{id}")
    public Mono<ResponseEntity<Conversation>> getConversation(@PathVariable String id) {
        return Mono.fromCallable(() -> conversationRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build()))
            .subscribeOn(Schedulers.boundedElastic());
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
        if (entry.getType() == null) {
            // MemoryService.persistMemoryToFile() 直接调用 entry.getType().name()，
            // 缺失 type 时会抛出未捕获的 NPE 并冒泡为 500；在入口处提前校验，
            // 返回可读的 400 错误而非让调用方看到一个模糊的服务端异常
            return ResponseEntity.badRequest().body("type is required (one of: USER, FEEDBACK, PROJECT, REFERENCE)");
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

    /**
     * 记忆搜索。
     *
     * <p>参数：
     * <ul>
     *   <li>{@code keyword}（必填）：搜索词</li>
     *   <li>{@code mode}（可选）：{@code keyword}（默认）或 {@code semantic}（向量语义搜索）</li>
     *   <li>{@code topK}（可选，仅 semantic 模式有效）：返回条数上限，默认使用配置值</li>
     * </ul>
     *
     * <p>semantic 模式需 {@code memory.embedding.enabled=true}，未启用时自动降级为 keyword 模式。
     */
    @GetMapping("/memory/{userId}/search")
    public Mono<ResponseEntity<List<MemoryEntry>>> searchMemories(
            @PathVariable String userId,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "keyword") String mode,
            @RequestParam(defaultValue = "0") int topK,
            ServerWebExchange exchange) {
        String effectiveUserId = resolveUserId(userId, exchange);

        if ("semantic".equalsIgnoreCase(mode)) {
            // 语义向量搜索（Embedding 服务未启用时内部自动降级）
            return memoryService.semanticSearch(effectiveUserId, keyword, topK)
                    .map(ResponseEntity::ok);
        }

        // 关键词搜索（wrap in subscribeOn 避免在 Netty IO 线程执行文件/in-memory 操作）
        return Mono.fromCallable(() -> memoryService.searchMemories(effectiveUserId, keyword))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    /**
     * 返回当前 Embedding 服务状态。
     */
    @GetMapping("/memory/embedding-status")
    public ResponseEntity<Map<String, Object>> embeddingStatus() {
        boolean enabled = memoryService.isEmbeddingEnabled();
        return ResponseEntity.ok(Map.of(
                "enabled", enabled,
                "note", enabled
                        ? "Semantic search available: GET /memory/{userId}/search?keyword=...&mode=semantic"
                        : "Embedding disabled. Set memory.embedding.enabled=true to enable."
        ));
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
     * userId 解析策略（优先级递减）：
     * 1. JWT exchange attribute（JwtAuthFilter 设置，最可信）
     * 2. X-User-Id 请求头（API Key 模式或非认证模式）
     * 3. 路径参数（向后兼容）
     * 4. 退化为 "default"
     */
    private String resolveUserId(String pathUserId, ServerWebExchange exchange) {
        String fromJwt = exchange.getAttribute(JwtAuthFilter.USER_ID_ATTR);
        if (fromJwt != null && !fromJwt.isBlank()) return fromJwt.trim();
        String headerUserId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
        if (headerUserId != null && !headerUserId.isBlank()) return headerUserId.trim();
        if (pathUserId != null && !pathUserId.isBlank()) return pathUserId.trim();
        return "default";
    }
}
