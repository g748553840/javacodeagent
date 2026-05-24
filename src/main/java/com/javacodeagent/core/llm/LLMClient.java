package com.javacodeagent.core.llm;

import com.javacodeagent.core.model.ConversationContext;
import com.javacodeagent.core.model.LLMResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface LLMClient {
    Mono<LLMResponse> chat(ConversationContext context);
    Flux<String> chatStream(ConversationContext context);
}