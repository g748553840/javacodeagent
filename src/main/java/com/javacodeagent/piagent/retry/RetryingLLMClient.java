package com.javacodeagent.piagent.retry;

import com.javacodeagent.core.llm.LLMClient;
import com.javacodeagent.core.llm.LLMStreamChunk;
import com.javacodeagent.core.model.ConversationContext;
import com.javacodeagent.core.model.LLMResponse;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 为 {@link LLMClient} 增加重试能力的装饰器。
 *
 * <p>设计参照 pi 的 {@code retryAssistantCall}（{@code packages/ai/src/utils/retry.ts}）：
 * <ul>
 *   <li>判定优先级：aborted 终态 → 非错误即成功 → 次数耗尽或不可重试则放弃 → 否则退避重试</li>
 *   <li>纯指数退避，无 jitter（jitter 属于 provider SDK 层职责）</li>
 *   <li>退避期间被中止不抛异常，而是归一化为一个失败响应</li>
 * </ul>
 *
 * <p><b>流式调用不重试</b>：{@code chatStream} / {@code chatStreamFull} 已经向客户端
 * emit 了部分 token，重试会导致内容重复。要支持需在 SSE 层实现"丢弃已发送前缀"协议，
 * 复杂度远高于收益。流式失败由上层以 error chunk 呈现给用户。
 */
@Slf4j
public class RetryingLLMClient implements LLMClient {

    private final LLMClient delegate;
    private final RetryableErrorClassifier classifier;
    private final RetryPolicy policy;

    public RetryingLLMClient(LLMClient delegate,
                             RetryableErrorClassifier classifier,
                             RetryPolicy policy) {
        this.delegate = delegate;
        this.classifier = classifier;
        this.policy = policy;
    }

    @Override
    public Mono<LLMResponse> chat(ConversationContext context) {
        return attempt(() -> delegate.chat(context), 1, policy.effectiveMaxAttempts());
    }

    private Mono<LLMResponse> attempt(Supplier<Mono<LLMResponse>> call,
                                      int attemptNo, int maxAttempts) {
        return call.get()
            // 客户端内部已把异常转成 error 响应；这里的 onErrorResume 是兜底，
            // 防止未来有实现直接抛异常而绕过重试逻辑。
            .onErrorResume(e -> Mono.just(LLMResponse.ofError(
                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())))
            .flatMap(resp -> {
                if (!isFailure(resp)) {
                    if (attemptNo > 1) {
                        log.info("LLM call succeeded on attempt {}/{}", attemptNo, maxAttempts);
                    }
                    return Mono.just(resp);
                }

                String errorText = resp.getErrorMessage();

                if (attemptNo >= maxAttempts) {
                    log.warn("LLM call failed after {} attempt(s), giving up: {}",
                        attemptNo, abbreviate(errorText));
                    return Mono.just(resp);
                }
                if (!classifier.isRetryable(errorText)) {
                    log.warn("LLM call failed with non-retryable error, giving up: {}",
                        abbreviate(errorText));
                    return Mono.just(resp);
                }

                Duration delay = policy.delayForAttempt(attemptNo);
                log.info("LLM attempt {}/{} failed ({}), retrying in {}ms",
                    attemptNo, maxAttempts, abbreviate(errorText), delay.toMillis());

                return Mono.delay(delay)
                    .flatMap(tick -> attempt(call, attemptNo + 1, maxAttempts));
            });
    }

    /**
     * 判定响应是否为失败。
     *
     * <p>兼容两种表达：新的 {@code error} 布尔标记，以及 {@code stopReason="error"}。
     * 前者是本次改造引入的显式信号，后者兼容可能存在的其他产出路径。
     */
    private boolean isFailure(LLMResponse resp) {
        if (resp == null) {
            return true;
        }
        return resp.isError() || "error".equals(resp.getStopReason());
    }

    private String abbreviate(String s) {
        if (s == null) return "(no message)";
        return s.length() <= 120 ? s : s.substring(0, 120) + "...";
    }

    // ── 流式：透传，不重试 ────────────────────────────────────────────

    @Override
    public Flux<String> chatStream(ConversationContext context) {
        return delegate.chatStream(context);
    }

    @Override
    public Flux<LLMStreamChunk> chatStreamFull(ConversationContext context) {
        return delegate.chatStreamFull(context);
    }
}
