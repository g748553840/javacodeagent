package com.javacodeagent.core.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javacodeagent.core.conversation.ResponseParser.ParsedResponse;
import com.javacodeagent.core.enums.MessageType;
import com.javacodeagent.core.hook.HookContext;
import com.javacodeagent.core.hook.HookManager;
import com.javacodeagent.core.hook.HookResult;
import com.javacodeagent.core.hook.HookType;
import com.javacodeagent.core.llm.LLMClient;
import com.javacodeagent.core.llm.LLMStreamChunk;
import com.javacodeagent.core.model.ConversationContext;
import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.core.model.Message;
import com.javacodeagent.core.model.ToolCall;
import com.javacodeagent.core.model.ToolExecutionResult;
import com.javacodeagent.core.tool.ToolManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationManager {

    private final LLMClient llmClient;
    private final ToolManager toolManager;
    private final ContextBuilder contextBuilder;
    private final ContextCompressor compressor;
    private final ResponseParser responseParser;
    private final ObjectMapper objectMapper;
    private final MessagePersistenceService messagePersistence;
    private final HookManager hookManager;

    private static final int MAX_TOOL_CALL_DEPTH = 10;

    // -------------------------------------------------------------------------
    // 公开入口
    // -------------------------------------------------------------------------

    public Mono<ConversationResponse> processMessage(ConversationRequest request) {
        if (request.getConversationId() == null) {
            request.setConversationId(UUID.randomUUID().toString());
        }
        String convId = request.getConversationId();
        // loadHistory() 是阻塞 JPA 调用，必须在 boundedElastic 线程执行
        return Mono.fromCallable(() -> messagePersistence.loadHistory(convId))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(history -> {
                ConversationContext context = contextBuilder.build(request, history);
                return processWithToolCalls(context, 0);
            });
    }

    /**
     * 流式 SSE 端点。
     * 使用 chatStreamFull() 实现 Token 级流式 + 工具调用协作：
     *   - 工具调用：先 emit tool_start / tool_result 事件，再继续下一轮 LLM
     *   - 文本：逐 token emit content 事件
     *   - 结束：emit done 事件
     */
    public Flux<String> processMessageStream(ConversationRequest request) {
        if (request.getConversationId() == null) {
            request.setConversationId(UUID.randomUUID().toString());
        }
        String conversationId = request.getConversationId();
        // loadHistory() 是阻塞 JPA 调用，必须在 boundedElastic 线程执行
        return Mono.fromCallable(() -> messagePersistence.loadHistory(conversationId))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany(history -> {
                ConversationContext context = contextBuilder.build(request, history);
                return executeStreamingLoop(context, 0, conversationId);
            });
    }

    public Mono<ConversationResponse> processWithHistory(
            ConversationRequest request, List<Message> history) {
        if (request.getConversationId() == null) {
            request.setConversationId(UUID.randomUUID().toString());
        }
        ConversationContext context = contextBuilder.build(request, history);
        return processWithToolCalls(context, 0);
    }

    // -------------------------------------------------------------------------
    // 非流式 Agentic Loop
    // -------------------------------------------------------------------------

    private Mono<ConversationResponse> processWithToolCalls(ConversationContext context, int depth) {
        if (depth > MAX_TOOL_CALL_DEPTH) {
            return Mono.just(ConversationResponse.builder()
                .content("Maximum tool call depth (" + MAX_TOOL_CALL_DEPTH + ") exceeded")
                .conversationId(context.getConversationId())
                .build());
        }

        return compressor.compress(context)
            .flatMap(compressed -> llmClient.chat(compressed)
                // publishOn 将 flatMap 回调切换到 boundedElastic：
                // persistNewMessages()、saveMessages()、hookManager.triggerHook() 均为阻塞调用，
                // 不能在 Netty IO 线程（WebClient 投递事件的线程）上执行。
                // subscribeOn 只影响订阅线程，不影响事件投递线程，此处必须用 publishOn。
                .publishOn(Schedulers.boundedElastic())
                .flatMap(response -> {
                    ParsedResponse parsed = responseParser.parse(response);

                    if (parsed.isHasToolCalls()) {
                        return handleToolCalls(parsed, compressed, depth);
                    }

                    // PRE_RESPONSE hook — 可拦截最终回复
                    String textContent = parsed.getTextContent();
                    HookContext preRespCtx = HookContext.builder()
                        .type(HookType.PRE_RESPONSE)
                        .userId(compressed.getUserId())
                        .conversationId(compressed.getConversationId())
                        .data(Map.of("content", textContent != null ? textContent : ""))
                        .build();
                    HookResult preRespResult = hookManager.triggerHook(HookType.PRE_RESPONSE, preRespCtx);
                    if (!preRespResult.shouldContinue()) {
                        return Mono.just(ConversationResponse.builder()
                            .content("Response blocked by pre-response hook: " + preRespResult.getMessage())
                            .conversationId(compressed.getConversationId())
                            .build());
                    }

                    // 持久化本轮新消息（用户消息 + 助手回复）
                    persistNewMessages(compressed);

                    ConversationResponse conversationResponse = ConversationResponse.builder()
                        .content(textContent)
                        .conversationId(compressed.getConversationId())
                        .build();

                    // POST_RESPONSE hook（通知型，不阻断）
                    hookManager.triggerHook(HookType.POST_RESPONSE, HookContext.builder()
                        .type(HookType.POST_RESPONSE)
                        .userId(compressed.getUserId())
                        .conversationId(compressed.getConversationId())
                        .data(Map.of("content", textContent != null ? textContent : ""))
                        .build());

                    return Mono.just(conversationResponse);
                }));
    }

    private Mono<ConversationResponse> handleToolCalls(
            ParsedResponse parsed, ConversationContext context, int depth) {

        List<Message> newMessages = new ArrayList<>();

        newMessages.add(Message.builder()
            .type(MessageType.ASSISTANT)
            .content(parsed.getTextContent())
            .toolCalls(parsed.getToolCalls())
            .build());

        for (ToolCall toolCall : parsed.getToolCalls()) {
            log.info("Executing tool: {} (id={})", toolCall.getName(), toolCall.getId());
            ToolExecutionResult result = toolManager.executeToolCall(
                toolCall, toExecutionContext(context));
            log.info("Tool {} → success={}", toolCall.getName(), result.isSuccess());

            newMessages.add(Message.builder()
                .type(MessageType.TOOL_RESULT)
                .content(result.isSuccess() ? result.getContent() : "Error: " + result.getError())
                .toolCallId(toolCall.getId())
                .build());
        }

        messagePersistence.saveMessages(context.getConversationId(), newMessages);

        ConversationContext nextContext = contextBuilder.buildWithResults(context, newMessages);
        return processWithToolCalls(nextContext, depth + 1);
    }

    // -------------------------------------------------------------------------
    // Token 级流式 Agentic Loop（使用 chatStreamFull）
    // -------------------------------------------------------------------------

    private Flux<String> executeStreamingLoop(
            ConversationContext context, int depth, String conversationId) {

        if (depth > MAX_TOOL_CALL_DEPTH) {
            return Flux.just(sseEvent("error",
                Map.of("message", "Maximum tool call depth exceeded")));
        }

        return compressor.compress(context)
            .flatMapMany(compressed ->
                processStreamChunks(compressed, depth, conversationId));
    }

    /**
     * 订阅 chatStreamFull() 并按 chunk 类型分发：
     *   TEXT      → content SSE event（逐 token）
     *   TOOL_CALL → 积累工具调用，待 DONE 后批量执行
     *   DONE      → 若有工具调用则执行并递归；否则 emit done
     */
    private Flux<String> processStreamChunks(
            ConversationContext context, int depth, String conversationId) {

        // 用可变容器积累工具调用和文本（Flux 回调中不能用局部变量）
        List<LLMStreamChunk> toolChunks = new ArrayList<>();
        StringBuilder textAccum = new StringBuilder();

        return llmClient.chatStreamFull(context)
            // publishOn 确保 concatMap 回调（含阻塞工具调用、JPA 持久化）在 boundedElastic 执行。
            // subscribeOn 仅影响订阅线程，WebClient 仍在 Netty IO 线程投递 chunk，不能替代 publishOn。
            .publishOn(Schedulers.boundedElastic())
            .concatMap(chunk -> switch (chunk.getType()) {
                case TEXT -> {
                    textAccum.append(chunk.getText());
                    yield Flux.just(sseEvent("content", Map.of("text", chunk.getText())));
                }
                case TOOL_CALL -> {
                    toolChunks.add(chunk);
                    yield Flux.empty();
                }
                case DONE -> {
                    if (!toolChunks.isEmpty()) {
                        // 执行所有工具调用，然后递归
                        yield Flux.defer(() ->
                            executeToolsAndContinue(
                                toolChunks.stream().map(LLMStreamChunk::getToolCall).toList(),
                                textAccum.toString(),
                                context, depth, conversationId));
                    } else {
                        // PRE_RESPONSE hook — 可拦截（例如内容过滤）
                        String accumulated = textAccum.toString();
                        HookResult preResult = hookManager.triggerHook(HookType.PRE_RESPONSE,
                            HookContext.builder()
                                .type(HookType.PRE_RESPONSE)
                                .userId(context.getUserId())
                                .conversationId(conversationId)
                                .data(Map.of("content", accumulated))
                                .build());
                        if (!preResult.shouldContinue()) {
                            yield Flux.just(sseEvent("error",
                                Map.of("message", "Response blocked: " + preResult.getMessage())));
                        } else {
                            String doneEvent = sseEvent("done", Map.of("conversationId", conversationId));
                            // POST_RESPONSE hook（通知型）
                            hookManager.triggerHook(HookType.POST_RESPONSE,
                                HookContext.builder()
                                    .type(HookType.POST_RESPONSE)
                                    .userId(context.getUserId())
                                    .conversationId(conversationId)
                                    .data(Map.of("content", accumulated))
                                    .build());
                            yield Flux.just(doneEvent);
                        }
                    }
                }
                case ERROR -> Flux.just(sseEvent("error",
                    Map.of("message", chunk.getError() != null ? chunk.getError() : "Stream error")));
            });
    }

    private Flux<String> executeToolsAndContinue(
            List<ToolCall> toolCalls, String assistantText,
            ConversationContext context, int depth, String conversationId) {

        List<String> events = new ArrayList<>();
        List<Message> newMessages = new ArrayList<>();

        // 助手消息（含工具调用记录）
        newMessages.add(Message.builder()
            .type(MessageType.ASSISTANT)
            .content(assistantText)
            .toolCalls(toolCalls)
            .build());

        for (ToolCall tc : toolCalls) {
            events.add(sseEvent("tool_start",
                Map.of("tool", tc.getName(), "id", tc.getId())));

            log.info("Stream loop: executing tool {} (id={})", tc.getName(), tc.getId());
            ToolExecutionResult result = toolManager.executeToolCall(tc, toExecutionContext(context));

            events.add(sseEvent("tool_result",
                Map.of("tool", tc.getName(),
                       "success", result.isSuccess(),
                       "preview", preview(result))));

            newMessages.add(Message.builder()
                .type(MessageType.TOOL_RESULT)
                .content(result.isSuccess() ? result.getContent() : "Error: " + result.getError())
                .toolCallId(tc.getId())
                .build());
        }

        messagePersistence.saveMessages(conversationId, newMessages);

        ConversationContext nextContext = contextBuilder.buildWithResults(context, newMessages);
        return Flux.fromIterable(events)
            .concatWith(executeStreamingLoop(nextContext, depth + 1, conversationId));
    }

    // -------------------------------------------------------------------------
    // 工具方法
    // -------------------------------------------------------------------------

    private void persistNewMessages(ConversationContext context) {
        // 只持久化最后一条用户消息 + 助手回复（已在 context.messages 末尾）
        List<Message> msgs = context.getMessages();
        if (msgs.size() >= 2) {
            messagePersistence.saveMessages(context.getConversationId(),
                msgs.subList(msgs.size() - 2, msgs.size()));
        } else if (!msgs.isEmpty()) {
            messagePersistence.saveMessages(context.getConversationId(), msgs);
        }
    }

    private String sseEvent(String type, Map<String, Object> data) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", type);
            payload.putAll(data);
            return "data: " + objectMapper.writeValueAsString(payload) + "\n\n";
        } catch (Exception e) {
            return "data: {\"type\":\"" + type + "\"}\n\n";
        }
    }

    private String preview(ToolExecutionResult result) {
        if (!result.isSuccess() || result.getContent() == null) return "";
        return result.getContent().length() > 200
            ? result.getContent().substring(0, 200) + "..."
            : result.getContent();
    }

    private ExecutionContext toExecutionContext(ConversationContext context) {
        return ExecutionContext.builder()
            .userId(context.getUserId() != null ? context.getUserId() : "default")
            .conversationId(context.getConversationId())
            .permissionLevel(context.getPermissionLevel())
            .workingDirectory(context.getWorkingDirectory())
            .build();
    }
}
