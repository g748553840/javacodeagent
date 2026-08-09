package com.javacodeagent.core.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javacodeagent.core.conversation.ResponseParser.ParsedResponse;
import com.javacodeagent.core.enums.MessageType;
import com.javacodeagent.config.AgentConfig;
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
import com.javacodeagent.piagent.abort.AbortRegistry;
import com.javacodeagent.piagent.tool.AbortSignal;
import com.javacodeagent.piagent.tool.ToolBatchObserver;
import com.javacodeagent.piagent.tool.ToolBatchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
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

    /** 持久化时每轮保存的消息数量（用户消息 + 助手回复）。 */
    private static final int MESSAGES_PER_ROUND = 2;
    /** 工具调用结果的日志预览最大长度。 */
    private static final int PREVIEW_MAX_LENGTH = 200;

    private final LLMClient llmClient;
    private final ToolManager toolManager;
    private final ContextBuilder contextBuilder;
    private final ContextCompressor compressor;
    private final ResponseParser responseParser;
    private final ObjectMapper objectMapper;
    private final MessagePersistenceService messagePersistence;
    private final HookManager hookManager;
    private final AgentConfig agentConfig;
    private final AbortRegistry abortRegistry;

    // -------------------------------------------------------------------------
    // 公开入口
    // -------------------------------------------------------------------------

    public Mono<ConversationResponse> processMessage(ConversationRequest request) {
        if (request.getConversationId() == null) {
            request.setConversationId(UUID.randomUUID().toString());
        }
        String convId = request.getConversationId();

        // defer 保证登记发生在订阅时而非装配时——装配时登记会让一个从未被订阅的
        // Mono 在注册表里留下永远不会被释放的条目
        return Mono.defer(() -> {
                abortRegistry.register(convId);
                // loadHistory() 是阻塞 JPA 调用，必须在 boundedElastic 线程执行
                return Mono.fromCallable(() -> messagePersistence.loadHistory(convId))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(history -> {
                        ConversationContext context = contextBuilder.build(request, history);
                        return processWithToolCalls(context, 0);
                    });
            })
            .doOnCancel(() -> abortRegistry.abort(convId, "Client cancelled the request"))
            .doFinally(signal -> abortRegistry.release(convId));
    }

    /**
     * 流式 SSE 端点。
     * 使用 chatStreamFull() 实现 Token 级流式 + 工具调用协作：
     *   - 工具调用：实时 emit tool_start / tool_progress / tool_result 事件，再继续下一轮 LLM
     *   - 文本：逐 token emit content 事件
     *   - 结束：emit done 事件
     *
     * <p>客户端断开（SSE 连接关闭）会取消整条链路，进而中止在途工具——
     * 否则一个被用户关掉的页面仍会让服务端把 {@code npm install} 跑完。
     */
    public Flux<String> processMessageStream(ConversationRequest request) {
        if (request.getConversationId() == null) {
            request.setConversationId(UUID.randomUUID().toString());
        }
        String conversationId = request.getConversationId();

        return Flux.defer(() -> {
                abortRegistry.register(conversationId);
                // loadHistory() 是阻塞 JPA 调用，必须在 boundedElastic 线程执行
                return Mono.fromCallable(() -> messagePersistence.loadHistory(conversationId))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(history -> {
                        ConversationContext context = contextBuilder.build(request, history);
                        return executeStreamingLoop(context, 0, conversationId);
                    });
            })
            .doOnCancel(() -> abortRegistry.abort(conversationId, "Client disconnected"))
            .doFinally(signal -> abortRegistry.release(conversationId));
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
        if (depth > agentConfig.getMaxToolCallDepth()) {
            return Mono.just(ConversationResponse.builder()
                .content("Maximum tool call depth (" + agentConfig.getMaxToolCallDepth() + ") exceeded")
                .conversationId(context.getConversationId())
                .build());
        }

        AbortSignal signal = abortRegistry.signalFor(context.getConversationId());
        if (signal.isAborted()) {
            return Mono.just(ConversationResponse.builder()
                .content("Conversation aborted: " + signal.getReason())
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
                    // LLM 调用失败（重试耗尽或不可重试错误）时提前返回。
                    // 不能让失败响应走下面的正常路径——那会把 "Error: ..." 当作
                    // 助手回复持久化进对话历史，污染后续所有轮次的上下文。
                    if (response.isError()) {
                        log.warn("LLM call failed for conversation {}: {}",
                            compressed.getConversationId(), response.getErrorMessage());
                        return Mono.just(ConversationResponse.builder()
                            .content("LLM request failed: " + response.getErrorMessage())
                            .conversationId(compressed.getConversationId())
                            .build());
                    }

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

        List<ToolCall> toolCalls = parsed.getToolCalls();
        AbortSignal signal = abortRegistry.signalFor(context.getConversationId());

        return toolManager
            .executeBatch(toolCalls, toExecutionContext(context), signal, ToolBatchObserver.NOOP)
            // saveMessages 是阻塞 JPA 调用；executeBatch 的结果可能在任意工具线程上投递
            .publishOn(Schedulers.boundedElastic())
            .flatMap(batch -> {
                List<Message> newMessages = new ArrayList<>();
                newMessages.add(Message.builder()
                    .type(MessageType.ASSISTANT)
                    .content(parsed.getTextContent())
                    .toolCalls(toolCalls)
                    .build());
                newMessages.addAll(batch.messages());

                messagePersistence.saveMessages(context.getConversationId(), newMessages);

                if (batch.terminate()) {
                    log.info("Tool batch requested termination for conversation {} at depth {}",
                        context.getConversationId(), depth);
                    return Mono.just(ConversationResponse.builder()
                        .content(terminationContent(parsed.getTextContent(), batch))
                        .conversationId(context.getConversationId())
                        .build());
                }

                ConversationContext nextContext = contextBuilder.buildWithResults(context, newMessages);
                return processWithToolCalls(nextContext, depth + 1);
            });
    }

    // -------------------------------------------------------------------------
    // Token 级流式 Agentic Loop（使用 chatStreamFull）
    // -------------------------------------------------------------------------

    private Flux<String> executeStreamingLoop(
            ConversationContext context, int depth, String conversationId) {

        if (depth > agentConfig.getMaxToolCallDepth()) {
            return Flux.just(sseEvent("error",
                Map.of("message", "Maximum tool call depth exceeded")));
        }

        AbortSignal signal = abortRegistry.signalFor(conversationId);
        if (signal.isAborted()) {
            // 中止后不再发起新一轮 LLM 调用。用 done 而非 error 收尾：
            // 中止是用户主动要的结果，不是故障
            return Flux.just(sseEvent("done",
                Map.of("conversationId", conversationId, "aborted", true)));
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

    /**
     * 批量执行工具并继续对话循环，工具事件实时推送。
     *
     * <p><b>为什么要 sink</b>：工具现在可能并行执行，而 {@code executeBatch} 只在
     * 全部结束后返回一个结果对象。要让 {@code tool_start} 和 BashTool 的逐行输出
     * 在执行期间就到达浏览器，必须有一条独立于返回值的旁路——这就是 sink。
     * 改造前这段代码把事件攒进 List 再一次性发出，即便工具跑了两分钟，
     * 用户也是在结束的瞬间才同时看到「开始」和「结束」。
     *
     * <p><b>为什么是 mergeSequential 而不是 merge</b>：两者都会立即订阅两条流
     * （所以工具会真的开始跑），但 mergeSequential 保证第一条流的元素全部先发。
     * 用 merge 的话 {@code done} 可能插到尚未排空的 {@code tool_result} 前面，
     * 而客户端通常在收到 done 后就停止监听，那些结果就丢了。
     */
    private Flux<String> executeToolsAndContinue(
            List<ToolCall> toolCalls, String assistantText,
            ConversationContext context, int depth, String conversationId) {

        Sinks.Many<String> toolEvents = Sinks.many().unicast().onBackpressureBuffer();
        AbortSignal signal = abortRegistry.signalFor(conversationId);

        ToolBatchObserver observer = new ToolBatchObserver() {
            @Override
            public void onStart(ToolCall call) {
                toolEvents.tryEmitNext(sseEvent("tool_start",
                    Map.of("tool", call.getName(), "id", idOf(call))));
            }

            @Override
            public void onUpdate(ToolCall call, Map<String, Object> partial) {
                toolEvents.tryEmitNext(sseEvent("tool_progress",
                    Map.of("tool", call.getName(), "id", idOf(call), "data", partial)));
            }

            @Override
            public void onComplete(ToolCall call, ToolExecutionResult result) {
                toolEvents.tryEmitNext(sseEvent("tool_result",
                    Map.of("tool", call.getName(),
                           "id", idOf(call),
                           "success", result.isSuccess(),
                           "preview", preview(result))));
            }
        };

        Flux<String> continuation = toolManager
            .executeBatch(toolCalls, toExecutionContext(context), signal, observer)
            // 兜底：异常与取消路径也要关掉 sink，否则 mergeSequential 永远等不到第一条流结束
            .doFinally(sig -> toolEvents.tryEmitComplete())
            // saveMessages 是阻塞 JPA 调用
            .publishOn(Schedulers.boundedElastic())
            .flatMapMany(batch -> {
                // 正常路径在这里显式关闭，确保工具事件排在下面的续流之前
                toolEvents.tryEmitComplete();

                List<Message> newMessages = new ArrayList<>();
                newMessages.add(Message.builder()
                    .type(MessageType.ASSISTANT)
                    .content(assistantText)
                    .toolCalls(toolCalls)
                    .build());
                newMessages.addAll(batch.messages());

                messagePersistence.saveMessages(conversationId, newMessages);

                if (batch.terminate()) {
                    log.info("Tool batch requested termination for conversation {} at depth {}",
                        conversationId, depth);
                    String content = terminationContent(assistantText, batch);
                    return Flux.just(
                        sseEvent("content", Map.of("text", content)),
                        sseEvent("done", Map.of(
                            "conversationId", conversationId,
                            "terminatedByTool", true)));
                }

                ConversationContext nextContext = contextBuilder.buildWithResults(context, newMessages);
                return executeStreamingLoop(nextContext, depth + 1, conversationId);
            });

        return Flux.mergeSequential(toolEvents.asFlux(), continuation);
    }

    // -------------------------------------------------------------------------
    // 工具方法
    // -------------------------------------------------------------------------

    /**
     * 拼装因工具要求终止而提前结束时返回给用户的内容。
     *
     * <p>此时模型不会再有下一轮发言，助手文本（通常是"我已经拟好计划"）
     * 单独看信息量不足，因此把终止工具的输出（例如完整的计划正文）一并带上。
     */
    private String terminationContent(String assistantText, ToolBatchResult batch) {
        StringBuilder sb = new StringBuilder();
        if (assistantText != null && !assistantText.isBlank()) {
            sb.append(assistantText.trim());
        }
        for (ToolExecutionResult result : batch.results()) {
            if (result.isSuccess() && result.getContent() != null && !result.getContent().isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(result.getContent().trim());
            }
        }
        return sb.toString();
    }

    /** ToolCall.id 可能为空，而 Map.of 不接受 null 值。 */
    private static String idOf(ToolCall call) {
        return call.getId() != null ? call.getId() : "";
    }

    private void persistNewMessages(ConversationContext context) {
        // 只持久化最后一条用户消息 + 助手回复（已在 context.messages 末尾）
        List<Message> msgs = context.getMessages();
        if (msgs.size() >= MESSAGES_PER_ROUND) {
            messagePersistence.saveMessages(context.getConversationId(),
                msgs.subList(msgs.size() - MESSAGES_PER_ROUND, msgs.size()));
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
        return result.getContent().length() > PREVIEW_MAX_LENGTH
            ? result.getContent().substring(0, PREVIEW_MAX_LENGTH) + "..."
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
