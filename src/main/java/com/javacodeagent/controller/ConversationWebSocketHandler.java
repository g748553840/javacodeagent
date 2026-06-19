package com.javacodeagent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javacodeagent.core.conversation.ConversationManager;
import com.javacodeagent.core.conversation.ConversationRequest;
import com.javacodeagent.core.conversation.ConversationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
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
 *   data: {"type":"tool_result","tool":"&lt;name&gt;","success":true,"preview":"..."}\n\n
 *   data: {"type":"content","text":"&lt;text chunk&gt;"}\n\n
 *   data: {"type":"done","conversationId":"&lt;id&gt;"}\n\n
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ConversationWebSocketHandler {

    private final ConversationManager conversationManager;
    private final ObjectMapper objectMapper;

    /** sessionId → conversationId 映射，有界 LRU（最多保留 1000 条），防止内存无限增长 */
    private static final int MAX_SESSIONS = 1000;
    private final Map<String, String> activeSessions = Collections.synchronizedMap(
        new LinkedHashMap<>(256, 0.75f, true) {
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
     * 从请求头 X-User-Id 提取 userId，未提供则退化为 "default"。
     * 客户端可在每次请求中通过此头声明身份，实现多用户隔离。
     */
    private String extractUserId(ServerWebExchange exchange) {
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
                            ? chunk.substring(6).trim()
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
