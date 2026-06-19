package com.javacodeagent.core.llm;

import com.javacodeagent.core.model.ConversationContext;
import com.javacodeagent.core.model.LLMResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface LLMClient {

    /** 非流式：等待完整响应（包含工具调用信息）。 */
    Mono<LLMResponse> chat(ConversationContext context);

    /**
     * 原始 Token 级流式：仅返回文本 token 字符串，不含工具调用。
     * 用于前端实时打字机效果。
     */
    Flux<String> chatStream(ConversationContext context);

    /**
     * 结构化流式：返回 {@link LLMStreamChunk}，区分文本 token / 工具调用 / 结束。
     * 默认实现退化为非流式（chat() 结果转换），子类可覆盖提供真正的 token 级实现。
     */
    default Flux<LLMStreamChunk> chatStreamFull(ConversationContext context) {
        return chat(context).flatMapMany(resp -> {
            if (resp.getToolCalls() != null && !resp.getToolCalls().isEmpty()) {
                // Reactor forbids null elements — startWith(null) throws NPE even with .filter() after it.
                // Conditionally prepend a text chunk only when content is non-empty.
                Flux<LLMStreamChunk> prefix = (resp.getContent() != null && !resp.getContent().isEmpty())
                    ? Flux.just(LLMStreamChunk.text(resp.getContent()))
                    : Flux.empty();
                return prefix
                    .concatWith(Flux.fromIterable(resp.getToolCalls()).map(LLMStreamChunk::toolCall))
                    .concatWith(Flux.just(LLMStreamChunk.done(resp.getStopReason())));
            }
            String text = resp.getContent() != null ? resp.getContent() : "";
            return Flux.just(LLMStreamChunk.text(text), LLMStreamChunk.done(resp.getStopReason()));
        });
    }
}