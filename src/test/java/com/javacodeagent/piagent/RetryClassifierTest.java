package com.javacodeagent.piagent;

import com.javacodeagent.piagent.retry.RetryPolicy;
import com.javacodeagent.piagent.retry.RetryableErrorClassifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重试分类与退避策略单元测试。
 *
 * <p>重点覆盖两条容易出错的规则：
 * <ul>
 *   <li>不可重试模式必须<b>先于</b>可重试模式判定——否则同时含
 *       "rate limit" 和 "billing" 的消息会被误判为可重试</li>
 *   <li>未匹配任何模式的未知错误应保守地不重试</li>
 * </ul>
 */
class RetryClassifierTest {

    private RetryableErrorClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new RetryableErrorClassifier();
    }

    // ── 可重试：瞬时故障 ──────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "Overloaded",
        "429 Too Many Requests",
        "503 Service Unavailable",
        "502 Bad Gateway",
        "504 Gateway Timeout",
        "524 A Timeout Occurred",
        "500 Internal Server Error",
        "rate_limit_error: too many requests",
        "rate limit exceeded, please slow down",
        "fetch failed",
        "ECONNRESET",
        "ENOTFOUND api.anthropic.com",
        "EAI_AGAIN",
        "socket hang up",
        "Connection reset by peer",
        "Read timed out",
        "stream ended before message_stop",
        "ResourceExhausted",
        "The service is temporarily unavailable"
    })
    void retryableErrors(String message) {
        assertThat(classifier.isRetryable(message))
            .as("Expected retryable: %s", message)
            .isTrue();
    }

    // ── 不可重试：配额 / 认证 ────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "insufficient_quota",
        "You exceeded your current quota",
        "quota exceeded for this month",
        "Please check your billing details",
        "out of budget",
        "Monthly usage limit reached",
        "available balance is insufficient",
        "GoUsageLimitError",
        "FreeUsageLimitError",
        "invalid_api_key",
        "authentication_error",
        "401 Unauthorized",
        "403 Forbidden"
    })
    void nonRetryableErrors(String message) {
        assertThat(classifier.isRetryable(message))
            .as("Expected non-retryable: %s", message)
            .isFalse();
    }

    /**
     * 判定顺序的回归测试。
     *
     * <p>这条消息同时命中 "rate limit"（可重试）和 "billing"（不可重试）。
     * 若分类器先匹配可重试模式就会误判——对一个欠费账户反复重试毫无意义，
     * 只会把用户该看到的报错延后好几秒。
     */
    @Test
    void quotaErrorMentioningRateLimit_isNotRetryable() {
        String message = "429 rate limit exceeded — upgrade your billing plan to continue";
        assertThat(classifier.isRetryable(message)).isFalse();
    }

    @Test
    void unknownError_isNotRetryable() {
        assertThat(classifier.isRetryable("something completely unexpected happened"))
            .isFalse();
    }

    @Test
    void nullAndBlank_areNotRetryable() {
        assertThat(classifier.isRetryable((String) null)).isFalse();
        assertThat(classifier.isRetryable("")).isFalse();
        assertThat(classifier.isRetryable("   ")).isFalse();
    }

    // ── 异常链判定 ────────────────────────────────────────────────────────────

    @Test
    void retryableCauseNestedInExceptionChain_isDetected() {
        Throwable root = new java.net.SocketException("Connection reset by peer");
        Throwable wrapper = new RuntimeException("WebClient request failed", root);
        assertThat(classifier.isRetryable(wrapper)).isTrue();
    }

    @Test
    void nullThrowable_isNotRetryable() {
        assertThat(classifier.isRetryable((Throwable) null)).isFalse();
    }

    // ── 退避计算 ──────────────────────────────────────────────────────────────

    @Test
    void exponentialBackoff_hasNoJitter() {
        RetryPolicy policy = new RetryPolicy();
        policy.setBaseDelay(Duration.ofSeconds(1));

        // 纯指数：1s, 2s, 4s, 8s —— 每次调用结果必须完全一致（无随机成分）
        assertThat(policy.delayForAttempt(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.delayForAttempt(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delayForAttempt(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.delayForAttempt(4)).isEqualTo(Duration.ofSeconds(8));

        // 可重复性验证：同一 attempt 多次计算结果相同
        assertThat(policy.delayForAttempt(3)).isEqualTo(policy.delayForAttempt(3));
    }

    @Test
    void largeAttemptNumber_doesNotOverflow() {
        RetryPolicy policy = new RetryPolicy();
        policy.setBaseDelay(Duration.ofSeconds(1));
        // 移位被限制在 20 位以内，不应抛异常或产生负值
        assertThat(policy.delayForAttempt(100)).isPositive();
    }

    @Test
    void disabledPolicy_yieldsSingleAttempt() {
        RetryPolicy policy = new RetryPolicy();
        policy.setEnabled(false);
        policy.setMaxAttempts(5);
        assertThat(policy.effectiveMaxAttempts()).isEqualTo(1);
    }

    @Test
    void enabledPolicy_respectsMaxAttempts() {
        RetryPolicy policy = new RetryPolicy();
        policy.setEnabled(true);
        policy.setMaxAttempts(3);
        assertThat(policy.effectiveMaxAttempts()).isEqualTo(3);
    }
}
