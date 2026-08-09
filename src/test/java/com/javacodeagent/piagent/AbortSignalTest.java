package com.javacodeagent.piagent;

import com.javacodeagent.piagent.tool.AbortSignal;
import com.javacodeagent.piagent.tool.AbortedException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AbortSignal} 单元测试。
 *
 * <p>重点是并发正确性：注册回调与触发中止可能来自不同线程，
 * 任何一个回调都不能丢失，也不能被执行两次。
 */
class AbortSignalTest {

    @Test
    void initialStateIsNotAborted() {
        AbortSignal signal = new AbortSignal();
        assertThat(signal.isAborted()).isFalse();
        assertThatCode(signal::throwIfAborted).doesNotThrowAnyException();
    }

    @Test
    void throwIfAbortedThrowsAfterAbort() {
        AbortSignal signal = new AbortSignal();
        signal.abort("user cancelled");

        assertThat(signal.isAborted()).isTrue();
        assertThat(signal.getReason()).isEqualTo("user cancelled");
        assertThatThrownBy(signal::throwIfAborted)
            .isInstanceOf(AbortedException.class)
            .hasMessageContaining("user cancelled");
    }

    @Test
    void listenerFiresOnAbort() {
        AbortSignal signal = new AbortSignal();
        AtomicInteger fired = new AtomicInteger();
        signal.onAbort(fired::incrementAndGet);

        assertThat(fired.get()).isZero();
        signal.abort();
        assertThat(fired.get()).isEqualTo(1);
    }

    /** 已中止后再注册的回调应立即执行——否则清理动作会被静默丢弃。 */
    @Test
    void listenerRegisteredAfterAbortFiresImmediately() {
        AbortSignal signal = new AbortSignal();
        signal.abort();

        AtomicInteger fired = new AtomicInteger();
        signal.onAbort(fired::incrementAndGet);

        assertThat(fired.get()).isEqualTo(1);
    }

    /** abort 幂等：重复调用不应重复触发回调。 */
    @Test
    void abortIsIdempotent() {
        AbortSignal signal = new AbortSignal();
        AtomicInteger fired = new AtomicInteger();
        signal.onAbort(fired::incrementAndGet);

        signal.abort("first");
        signal.abort("second");
        signal.abort("third");

        assertThat(fired.get()).isEqualTo(1);
        assertThat(signal.getReason())
            .as("reason should be from the first abort call")
            .isEqualTo("first");
    }

    /** 一个回调抛异常不应阻止其余回调执行。 */
    @Test
    void failingListenerDoesNotBlockOthers() {
        AbortSignal signal = new AbortSignal();
        AtomicInteger fired = new AtomicInteger();

        signal.onAbort(() -> { throw new RuntimeException("cleanup failed"); });
        signal.onAbort(fired::incrementAndGet);
        signal.onAbort(fired::incrementAndGet);

        assertThatCode(signal::abort).doesNotThrowAnyException();
        assertThat(fired.get()).isEqualTo(2);
    }

    @Test
    void nullListenerIsIgnored() {
        AbortSignal signal = new AbortSignal();
        assertThatCode(() -> signal.onAbort(null)).doesNotThrowAnyException();
        assertThatCode(signal::abort).doesNotThrowAnyException();
    }

    /**
     * 并发注册与中止：每个回调恰好执行一次。
     *
     * <p>这是 {@code onAbort} 中双重检查逻辑的验证——注册与中止竞态时，
     * 回调既不能丢失（注册后信号已中止但回调没跑），也不能重复执行
     * （回调进了列表又被立即执行一次）。
     */
    @Test
    void concurrentRegistrationAndAbort_eachListenerFiresExactlyOnce() throws Exception {
        for (int round = 0; round < 50; round++) {
            AbortSignal signal = new AbortSignal();
            int listenerCount = 16;
            AtomicInteger totalFired = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(listenerCount + 1);

            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < listenerCount; i++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            signal.onAbort(totalFired::incrementAndGet);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                pool.submit(() -> {
                    try {
                        start.await();
                        signal.abort();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });

                start.countDown();
                assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(totalFired.get())
                .as("round %d: every listener must fire exactly once", round)
                .isEqualTo(listenerCount);
        }
    }

    /** NEVER 常量永不中止，可安全共享。 */
    @Test
    void neverSignalIsNeverAborted() {
        assertThat(AbortSignal.NEVER.isAborted()).isFalse();
        assertThatCode(AbortSignal.NEVER::throwIfAborted).doesNotThrowAnyException();
    }
}
