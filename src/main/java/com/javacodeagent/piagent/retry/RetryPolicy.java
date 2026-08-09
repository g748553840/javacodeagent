package com.javacodeagent.piagent.retry;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * LLM 调用重试策略。
 *
 * <p>设计参照 pi 的 {@code packages/ai/src/utils/retry.ts}：纯指数退避，无 jitter。
 * jitter 属于 provider SDK 层的职责（那一层还会优先采纳 {@code retry-after} 响应头），
 * 本层只负责应用可控的粗粒度重试。
 *
 * <p>与 pi 的一处有意偏离：pi 默认 {@code enabled=false}（重试是显式选择），
 * javacodeagent 默认开启——本项目面向的是长时间运行的 Agent 会话，
 * 一次瞬时 502 就中断整轮对话的代价远高于多等几秒。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.retry")
public class RetryPolicy {

    /** 是否启用重试。 */
    private boolean enabled = true;

    /** 总尝试次数（含首次调用），而非"额外重试次数"。 */
    private int maxAttempts = 3;

    /** 首次重试前的基础延迟，后续按 2 的幂次递增。 */
    private Duration baseDelay = Duration.ofSeconds(1);

    /**
     * 计算第 N 次尝试失败后的退避时长。
     *
     * <p>纯指数：{@code baseDelay * 2^(attempt-1)}。attempt 从 1 开始，
     * 即首次失败后等 baseDelay，第二次失败后等 2×baseDelay。
     */
    public Duration delayForAttempt(int attempt) {
        if (attempt < 1) {
            return baseDelay;
        }
        // 防御性上限：避免 attempt 过大导致移位溢出
        int shift = Math.min(attempt - 1, 20);
        return baseDelay.multipliedBy(1L << shift);
    }

    /** 有效尝试次数：未启用时恒为 1（只调一次，不重试）。 */
    public int effectiveMaxAttempts() {
        return enabled ? Math.max(1, maxAttempts) : 1;
    }
}
