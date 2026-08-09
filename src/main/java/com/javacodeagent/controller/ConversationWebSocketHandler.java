package com.javacodeagent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javacodeagent.config.JwtAuthFilter;
import com.javacodeagent.core.conversation.ConversationManager;
import com.javacodeagent.core.conversation.ConversationRequest;
import com.javacodeagent.core.conversation.ConversationResponse;
import com.javacodeagent.piagent.abort.AbortRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SSE 流式对话控制器。
 *
 * <p><b>认证</b>：由全局 {@link com.javacodeagent.config.ApiKeyAuthFilter} 统一处理，
 * Controller 层不再重复校验，避免双重认证逻辑不一致的问题。
 * 若需开启认证，在 {@code application.yml} 中配置：
 * <pre>
 *   security:
 *     api-key: your-secret-key
 * </pre>
 *
 * <p>SSE event format:
 * <pre>
 *   data: {"type":"tool_start","tool":"&lt;name&gt;","id":"&lt;id&gt;"}\n\n
 *   data: {"type":"tool_progress","tool":"&lt;name&gt;","id":"&lt;id&gt;","data":{"output":"..."}}\n\n
 *   data: {"type":"tool_result","tool":"&lt;name&gt;","id":"&lt;id&gt;","success":true,"preview":"..."}\n\n
 *   data: {"type":"content","text":"&lt;text chunk&gt;"}\n\n
 *   data: {"type":"done","conversationId":"&lt;id&gt;"}\n\n
 * </pre>
 *
 * <p>{@code tool_progress} 只有覆盖了流式执行入口的工具才会发（目前是 BashTool，
 * 逐行推送子进程输出）。{@code done} 事件可能带上 {@code terminatedByTool:true}
 * （工具要求交还控制权，如 ExitPlanMode）或 {@code aborted:true}（被中止）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ConversationWebSocketHandler {

    private final ConversationManager conversationManager;
    private final ObjectMapper objectMapper;
    private final AbortRegistry abortRegistry;

    /** sessionId → conversationId 映射，有界 LRU（最多保留 1000 条），防止内存无限增长 */
    private static final int MAX_SESSIONS = 1000;
    private static final int SESSION_MAP_INITIAL_CAPACITY = 256;
    private static final float SESSION_MAP_LOAD_FACTOR = 0.75f;
    private static final int SSE_DATA_PREFIX_LENGTH = 6; // "data: ".length()
    private final Map<String, String> activeSessions = Collections.synchronizedMap(
        new LinkedHashMap<>(SESSION_MAP_INITIAL_CAPACITY, SESSION_MAP_LOAD_FACTOR, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > MAX_SESSIONS;
            }
        }
    );

    // -------------------------------------------------------------------------
    // 工具方法
    // -------------------------------------------------------------------------

    /**
     * 提取 userId：优先读取 JWT 过滤器注入的 attribute（当 JWT 认证激活时），
     * 降级到 X-User-Id 请求头，最终默认为 "default"。
     */
    private String extractUserId(ServerWebExchange exchange) {
        Object jwtUserId = exchange.getAttribute(JwtAuthFilter.USER_ID_ATTR);
        if (jwtUserId != null) return jwtUserId.toString();
        String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
        return (userId != null && !userId.isBlank()) ? userId.trim() : "default";
    }

    // -------------------------------------------------------------------------
    // SSE 流式端点
    // -------------------------------------------------------------------------

    @PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(
            @RequestBody Map<String, String> body,
            ServerWebExchange exchange) {

        String sessionId = body.getOrDefault("sessionId", UUID.randomUUID().toString());
        String content = body.get("content");
        String userId = extractUserId(exchange);
        String conversationId = activeSessions.get(sessionId);

        log.debug("SSE stream: sessionId={}, userId={}, conversationId={}", sessionId, userId, conversationId);

        ConversationRequest request = ConversationRequest.builder()
            .conversationId(conversationId)
            .content(content)
            .userId(userId)
            .build();

        return conversationManager.processMessageStream(request)
            .doOnNext(chunk -> {
                // 从 "done" SSE 事件提取 conversationId 并持久化到 session 映射
                if (chunk.contains("\"type\":\"done\"") && chunk.contains("\"conversationId\"")) {
                    try {
                        String json = chunk.startsWith("data: ")
                            ? chunk.substring(SSE_DATA_PREFIX_LENGTH).trim()
                            : chunk.trim();
                        JsonNode node = objectMapper.readTree(json);
                        String newConvId = node.path("conversationId").asText(null);
                        if (newConvId != null) {
                            activeSessions.put(sessionId, newConvId);
                        }
                    } catch (Exception e) {
                        log.debug("Could not extract conversationId from SSE done event", e);
                    }
                }
            });
    }

    // -------------------------------------------------------------------------
    // 非流式端点
    // -------------------------------------------------------------------------

    @PostMapping("/chat")
    public Mono<ConversationResponse> chat(
            @RequestBody Map<String, String> body,
            ServerWebExchange exchange) {

        String sessionId = body.getOrDefault("sessionId", UUID.randomUUID().toString());
        String content = body.get("content");
        String userId = extractUserId(exchange);
        String conversationId = activeSessions.get(sessionId);

        ConversationRequest request = ConversationRequest.builder()
            .conversationId(conversationId)
            .content(content)
            .userId(userId)
            .build();

        return conversationManager.processMessage(request)
            .doOnNext(response -> {
                if (response.getConversationId() != null) {
                    activeSessions.put(sessionId, response.getConversationId());
                }
            });
    }

    // -------------------------------------------------------------------------
    // 中止
    // -------------------------------------------------------------------------

    /**
     * 中止一个正在执行的会话。
     *
     * <p>这是「停止」按钮的后端。它不等待也不回滚：只是把该会话的
     * {@link com.javacodeagent.piagent.tool.AbortSignal} 置位，由在途工具在下一个
     * 检查点自行退出（{@code BashTool} 会销毁子进程），Agent 循环则不再发起新一轮
     * LLM 调用。已经写入磁盘的文件、已经提交的 git commit 不会被撤销——
     * 中止的语义是"别再往下做了"，不是"当作没发生过"。
     *
     * <p>客户端直接断开 SSE 连接也会触发同样的中止，此端点用于
     * 长轮询/非流式调用，或者前端想在关闭连接前明确表达意图的场景。
     *
     * @return 404 表示该会话当前不在执行中（已结束或从未开始）
     */
    @PostMapping("/chat/{conversationId}/abort")
    public ResponseEntity<Map<String, Object>> abort(
            @PathVariable String conversationId,
            @RequestBody(required = false) Map<String, String> body) {

        String reason = body != null && body.get("reason") != null
            ? body.get("reason")
            : "Aborted by user";

        boolean aborted = abortRegistry.abort(conversationId, reason);
        if (!aborted) {
            log.debug("Abort requested for inactive conversation {}", conversationId);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
            "conversationId", conversationId,
            "aborted", true,
            "reason", reason));
    }

    // -------------------------------------------------------------------------
    // 会话管理
    // -------------------------------------------------------------------------

    @PostMapping("/sessions")
    public Mono<Map<String, String>> createSession() {
        String sessionId = UUID.randomUUID().toString();
        activeSessions.put(sessionId, null);
        return Mono.just(Map.of("sessionId", sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Mono<Void> removeSession(@PathVariable String sessionId) {
        activeSessions.remove(sessionId);
        return Mono.empty();
    }
}
