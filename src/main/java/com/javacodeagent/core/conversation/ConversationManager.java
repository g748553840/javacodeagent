package com.javacodeagent.core.conversation;

import com.javacodeagent.core.conversation.ResponseParser.ParsedResponse;
import com.javacodeagent.core.enums.MessageType;
import com.javacodeagent.core.llm.LLMClient;
import com.javacodeagent.core.model.ConversationContext;
import com.javacodeagent.core.model.LLMResponse;
import com.javacodeagent.core.model.Message;
import com.javacodeagent.core.model.ToolCall;
import com.javacodeagent.core.model.ToolExecutionResult;
import com.javacodeagent.core.tool.ToolManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
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

    /**
     * 最大工具调用循环深度
     */
    private static final int MAX_TOOL_CALL_DEPTH = 10;

    /**
     * 处理用户消息
     */
    public Mono<ConversationResponse> processMessage(ConversationRequest request) {
        String conversationId = request.getConversationId();
        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
        }

        ConversationContext context = contextBuilder.build(request, null);

        return processWithToolCalls(context, 0);
    }

    /**
     * 流式处理用户消息（SSE 流式响应）
     */
    public Flux<String> processMessageStream(ConversationRequest request) {
        String conversationId = request.getConversationId();
        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
        }

        ConversationContext context = contextBuilder.build(request, null);

        return processWithToolCalls(context, 0)
            .flatMapMany(response -> {
                String content = response.getContent();
                if (content == null || content.isEmpty()) {
                    return Flux.just("data: {\"content\":\"\"}\n\n");
                }

                // Split response into chunks for SSE streaming
                String[] words = content.split("(?<=\\s)");
                Flux<String> contentChunks = Flux.fromArray(words)
                    .map(word -> "data: " + word)
                    .concatWithValues(
                        "data: {\"conversationId\":\"" + response.getConversationId() + "\"}",
                        "data: [DONE]"
                    );

                return contentChunks;
            });
    }

    /**
     * 处理已有历史消息的新请求
     */
    public Mono<ConversationResponse> processWithHistory(
            ConversationRequest request, List<Message> history) {
        String conversationId = request.getConversationId();
        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
        }

        ConversationContext context = contextBuilder.build(request, history);

        return processWithToolCalls(context, 0);
    }

    /**
     * 工具调用循环处理
     */
    private Mono<ConversationResponse> processWithToolCalls(ConversationContext context, int depth) {
        if (depth > MAX_TOOL_CALL_DEPTH) {
            return Mono.just(ConversationResponse.builder()
                .content("Maximum tool call depth (" + MAX_TOOL_CALL_DEPTH + ") exceeded")
                .conversationId(context.getConversationId())
                .build());
        }

        // 压缩上下文（如果消息过多）
        ConversationContext compressedContext = compressor.compress(context);

        return llmClient.chat(compressedContext)
            .flatMap(response -> {
                ParsedResponse parsed = responseParser.parse(response);

                if (parsed.isHasToolCalls()) {
                    return handleToolCalls(parsed, compressedContext, depth);
                }

                return Mono.just(ConversationResponse.builder()
                    .content(parsed.getTextContent())
                    .conversationId(compressedContext.getConversationId())
                    .build());
            });
    }

    /**
     * 执行工具调用并将结果添加回消息列表，继续与 LLM 循环
     */
    private Mono<ConversationResponse> handleToolCalls(
            ParsedResponse parsed, ConversationContext context, int depth) {

        List<Message> newMessages = new ArrayList<>(context.getMessages());

        // 添加助手消息（带工具调用）
        newMessages.add(Message.builder()
            .type(MessageType.ASSISTANT)
            .content(parsed.getTextContent())
            .toolCalls(parsed.getToolCalls())
            .build());

        // 执行每个工具调用
        for (ToolCall toolCall : parsed.getToolCalls()) {
            log.info("Executing tool: {} with id: {}", toolCall.getName(), toolCall.getId());

            ToolExecutionResult result = toolManager.executeToolCall(
                toolCall,
                toExecutionContext(context)
            );

            // 添加工具调用结果
            newMessages.add(Message.builder()
                .type(MessageType.TOOL_RESULT)
                .content(result.isSuccess() ? result.getContent() : "Error: " + result.getError())
                .toolCallId(toolCall.getId())
                .build());

            log.info("Tool {} result: success={}", toolCall.getName(), result.isSuccess());
        }

        // 构建下一轮上下文并继续
        ConversationContext newContext = contextBuilder.buildWithResults(context, newMessages);

        return processWithToolCalls(newContext, depth + 1);
    }

    private com.javacodeagent.core.model.ExecutionContext toExecutionContext(ConversationContext context) {
        return com.javacodeagent.core.model.ExecutionContext.builder()
            .conversationId(context.getConversationId())
            .workingDirectory(context.getWorkingDirectory())
            .build();
    }
}
