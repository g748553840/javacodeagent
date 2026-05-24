package com.javacodeagent.controller;

import com.javacodeagent.core.conversation.ConversationManager;
import com.javacodeagent.core.conversation.ConversationRequest;
import com.javacodeagent.core.conversation.ConversationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Streaming conversation handler via SSE (Server-Sent Events).
 * Provides both single-response and streaming endpoints.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ConversationWebSocketHandler {

    private final ConversationManager conversationManager;
    private final Map<String, String> activeSessions = new ConcurrentHashMap<>();

    /**
     * SSE streaming endpoint for chat responses
     */
    @PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("sessionId", UUID.randomUUID().toString());
        String content = body.get("content");

        String conversationId = activeSessions.get(sessionId);

        ConversationRequest request = ConversationRequest.builder()
            .conversationId(conversationId)
            .content(content)
            .build();

        return conversationManager.processMessageStream(request)
            .doOnNext(chunk -> {
                if (chunk.contains("\"conversationId\"")) {
                    // Extract and store conversationId from metadata chunks
                    activeSessions.put(sessionId, chunk);
                }
            });
    }

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
