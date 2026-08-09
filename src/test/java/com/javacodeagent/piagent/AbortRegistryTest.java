package com.javacodeagent.piagent;

import com.javacodeagent.piagent.abort.AbortRegistry;
import com.javacodeagent.piagent.tool.AbortSignal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AbortRegistry} 单元测试。
 *
 * <p>这个类很小，但它有两个容易写错、且错了之后症状很隐蔽的点：
 * 未登记会话返回 null（导致下游 NPE），以及 release 漏调（导致单例 map 缓慢泄漏）。
 */
class AbortRegistryTest {

    @Test
    void unknownConversationYieldsNeverSignalRatherThanNull() {
        AbortRegistry registry = new AbortRegistry();

        AbortSignal signal = registry.signalFor("never-seen");

        assertThat(signal).isSameAs(AbortSignal.NEVER);
        assertThat(signal.isAborted()).isFalse();
    }

    @Test
    void nullConversationIdIsTolerated() {
        AbortRegistry registry = new AbortRegistry();

        assertThat(registry.signalFor(null)).isSameAs(AbortSignal.NEVER);
        assertThat(registry.abort(null, "whatever")).isFalse();
        registry.release(null);
        assertThat(registry.activeCount()).isZero();
    }

    @Test
    void registeredConversationCanBeAborted() {
        AbortRegistry registry = new AbortRegistry();
        AbortSignal signal = registry.register("conv-1");

        assertThat(registry.abort("conv-1", "user pressed stop")).isTrue();

        assertThat(signal.isAborted()).isTrue();
        assertThat(signal.getReason()).isEqualTo("user pressed stop");
        assertThat(registry.signalFor("conv-1")).isSameAs(signal);
    }

    @Test
    void abortReturnsFalseForConversationThatAlreadyFinished() {
        AbortRegistry registry = new AbortRegistry();
        registry.register("conv-1");
        registry.release("conv-1");

        assertThat(registry.abort("conv-1", "too late"))
            .as("controller relies on this to answer 404 instead of pretending it worked")
            .isFalse();
    }

    @Test
    void releaseIsIdempotentAndLeavesNothingBehind() {
        AbortRegistry registry = new AbortRegistry();
        registry.register("conv-1");
        registry.register("conv-2");
        assertThat(registry.activeCount()).isEqualTo(2);

        registry.release("conv-1");
        registry.release("conv-1");
        registry.release("conv-2");

        assertThat(registry.activeCount()).isZero();
    }

    @Test
    void reRegisteringReplacesSignalWithoutAbortingTheOldOne() {
        AbortRegistry registry = new AbortRegistry();
        AbortSignal first = registry.register("conv-1");
        AbortSignal second = registry.register("conv-1");

        assertThat(second).isNotSameAs(first);
        assertThat(first.isAborted())
            .as("whether the previous round should stop is the conversation layer's call, "
              + "not the registry's")
            .isFalse();
        assertThat(registry.signalFor("conv-1")).isSameAs(second);
    }
}
