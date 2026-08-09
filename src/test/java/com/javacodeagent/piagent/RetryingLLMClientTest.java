package com.javacodeagent.piagent;

import com.javacodeagent.core.llm.LLMClient;
import com.javacodeagent.core.llm.LLMStreamChunk;
import com.javacodeagent.core.model.ConversationContext;
import com.javacodeagent.core.model.LLMResponse;
import com.javacodeagent.piagent.retry.RetryPolicy;
import com.javacodeagent.piagent.retry.RetryableErrorClassifier;
import com.javacodeagent.piagent.retry.RetryingLLMClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RetryingLLMClient} 行为测试。
 *
 * <p>用极短的 baseDelay（10ms）保证测试快速完成，同时仍能验证退避确实发生。
 */
class RetryingLLMClientTest {

    private RetryableErrorClassifier classifier;
    private RetryPolicy policy;

    @BeforeEach
    void setUp() {
        classifier = new RetryableErrorClassifier();
        policy = new RetryPolicy();
        policy.setEnabled(true);
        policy.setMaxAttempts(3);
        policy.setBaseDelay(Duration.ofMillis(10));
    }

    /** 可重试错误应在后续尝试成功后返回成功结果。 */
    @Test
    void retriesOnTransientError_thenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        LLMClient flaky = stub(() -> {
            int n = calls.incrementAndGet();
            return n < 3
                ? LLMResponse.ofError("503 Service Unavailable")
                : LLMResponse.builder().content("ok").build();
        });

        RetryingLLMClient client = new RetryingLLMClient(flaky, classifier, policy);

        StepVerifier.create(client.chat(ctx()))
            .assertNext(resp -> {
                assertThat(resp.isError()).isFalse();
                assertThat(resp.getContent()).isEqualTo("ok");
            })
            .verifyComplete();

        assertThat(calls.get()).isEqualTo(3);
    }

    /** 不可重试错误必须立即返回，不做任何重试。 */
    @Test
    void doesNotRetryOnQuotaError() {
        AtomicInteger calls = new AtomicInteger();
        LLMClient broke = stub(() -> {
            calls.incrementAndGet();
            return LLMResponse.ofError("insufficient_quota: please add credits");
        });

        RetryingLLMClient client = new RetryingLLMClient(broke, classifier, policy);

        StepVerifier.create(client.chat(ctx()))
            .assertNext(resp -> assertThat(resp.isError()).isTrue())
            .verifyComplete();

        assertThat(calls.get())
            .as("quota errors must not be retried")
            .isEqualTo(1);
    }

    /** 次数耗尽后返回最后一次的错误响应，而非无限重试。 */
    @Test
    void givesUpAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();
        LLMClient alwaysDown = stub(() -> {
            calls.incrementAndGet();
            return LLMResponse.ofError("502 Bad Gateway");
        });

        RetryingLLMClient client = new RetryingLLMClient(alwaysDown, classifier, policy);

        StepVerifier.create(client.chat(ctx()))
            .assertNext(resp -> assertThat(resp.isError()).isTrue())
            .verifyComplete();

        assertThat(calls.get()).isEqualTo(3);
    }

    /** 首次即成功时不应产生额外调用。 */
    @Test
    void noRetryWhenFirstAttemptSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        LLMClient healthy = stub(() -> {
            calls.incrementAndGet();
            return LLMResponse.builder().content("hello").build();
        });

        RetryingLLMClient client = new RetryingLLMClient(healthy, classifier, policy);

        StepVerifier.create(client.chat(ctx()))
            .assertNext(resp -> assertThat(resp.getContent()).isEqualTo("hello"))
            .verifyComplete();

        assertThat(calls.get()).isEqualTo(1);
    }

    /** 策略关闭时只调一次。 */
    @Test
    void disabledPolicy_callsOnce() {
        policy.setEnabled(false);
        AtomicInteger calls = new AtomicInteger();
        LLMClient flaky = stub(() -> {
            calls.incrementAndGet();
            return LLMResponse.ofError("503 Service Unavailable");
        });

        RetryingLLMClient client = new RetryingLLMClient(flaky, classifier, policy);
        client.chat(ctx()).block();

        assertThat(calls.get()).isEqualTo(1);
    }

    /**
     * 流式调用不得重试。
     *
     * <p>已经 emit 给客户端的 token 无法撤回，重试会造成内容重复。
     */
    @Test
    void streamingIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        LLMClient streamFails = new LLMClient() {
            @Override
            public Mono<LLMResponse> chat(ConversationContext context) {
                return Mono.just(LLMResponse.builder().content("x").build());
            }

            @Override
            public Flux<String> chatStream(ConversationContext context) {
                calls.incrementAndGet();
                return Flux.just("partial");
            }
        };

        RetryingLLMClient client = new RetryingLLMClient(streamFails, classifier, policy);
        client.chatStream(ctx()).collectList().block();

        assertThat(calls.get()).isEqualTo(1);
    }

    /** stopReason=error 也应被识别为失败（兼容未设置 error 标记的产出路径）。 */
    @Test
    void stopReasonErrorIsTreatedAsFailure() {
        AtomicInteger calls = new AtomicInteger();
        LLMClient client0 = stub(() -> {
            int n = calls.incrementAndGet();
            return n < 2
                ? LLMResponse.builder().stopReason("error").errorMessage("429 rate limit").build()
                : LLMResponse.builder().content("recovered").build();
        });

        RetryingLLMClient client = new RetryingLLMClient(client0, classifier, policy);
        LLMResponse result = client.chat(ctx()).block();

        assertThat(result).isNotNull();
        assertThat(result.getContent()).isEqualTo("recovered");
        assertThat(calls.get()).isEqualTo(2);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private LLMClient stub(java.util.function.Supplier<LLMResponse> supplier) {
        return new LLMClient() {
            @Override
            public Mono<LLMResponse> chat(ConversationContext context) {
                return Mono.fromSupplier(supplier);
            }

            @Override
            public Flux<String> chatStream(ConversationContext context) {
                return Flux.empty();
            }

            @Override
            public Flux<LLMStreamChunk> chatStreamFull(ConversationContext context) {
                return Flux.empty();
            }
        };
    }

    private ConversationContext ctx() {
        return ConversationContext.builder()
            .conversationId("test")
            .messages(List.of())
            .availableTools(List.of())
            .build();
    }
}
