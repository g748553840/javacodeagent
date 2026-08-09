package com.javacodeagent.piagent.retry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 判定 LLM 错误是否值得重试。
 *
 * <p>设计参照 pi 的 {@code RETRYABLE_PROVIDER_ERROR_PATTERN} /
 * {@code NON_RETRYABLE_PROVIDER_LIMIT_ERROR_PATTERN}：基于错误文本的正则匹配，
 * 而非错误码分类。理由是各家 provider 的错误码体系互不兼容，
 * 但错误文本里的关键词（overloaded / quota exceeded）相当稳定。
 *
 * <p><b>判定顺序至关重要</b>：先排除不可重试模式，再匹配可重试模式。
 * 若顺序颠倒，"rate limit exceeded, upgrade your billing plan" 这类
 * 同时命中两边的消息会被误判为可重试，导致对配额耗尽的账户做无意义的重试。
 */
@Slf4j
@Component
public class RetryableErrorClassifier {

    /**
     * 配额 / 账单类错误——重试无意义，只会浪费时间并延后向用户报错。
     * 这类错误需要人工干预（充值、升级套餐），退避多久都不会自愈。
     */
    private static final List<Pattern> NON_RETRYABLE = List.of(
        Pattern.compile("insufficient[_\\s]*quota", Pattern.CASE_INSENSITIVE),
        Pattern.compile("quota\\s+exceeded", Pattern.CASE_INSENSITIVE),
        Pattern.compile("billing", Pattern.CASE_INSENSITIVE),
        Pattern.compile("out\\s+of\\s+budget", Pattern.CASE_INSENSITIVE),
        Pattern.compile("usage\\s+limit\\s+reached", Pattern.CASE_INSENSITIVE),
        Pattern.compile("available\\s+balance", Pattern.CASE_INSENSITIVE),
        Pattern.compile("GoUsageLimitError"),
        Pattern.compile("FreeUsageLimitError"),
        Pattern.compile("invalid[_\\s]*api[_\\s]*key", Pattern.CASE_INSENSITIVE),
        Pattern.compile("authentication[_\\s]*error", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b401\\b"),
        Pattern.compile("\\b403\\b")
    );

    /**
     * 瞬时故障类——服务端过载、网络抖动、流被截断，这些等一等大概率能成。
     */
    private static final List<Pattern> RETRYABLE = List.of(
        Pattern.compile("overloaded", Pattern.CASE_INSENSITIVE),
        Pattern.compile("rate.?limit", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(429|500|502|503|504|524)\\b"),
        Pattern.compile("fetch\\s+failed", Pattern.CASE_INSENSITIVE),
        Pattern.compile("ENOTFOUND|EAI_AGAIN|ECONNRESET|ECONNREFUSED|EPIPE"),
        Pattern.compile("socket\\s+hang\\s+up", Pattern.CASE_INSENSITIVE),
        Pattern.compile("connection\\s+reset", Pattern.CASE_INSENSITIVE),
        Pattern.compile("timeout|timed\\s+out", Pattern.CASE_INSENSITIVE),
        Pattern.compile("ended\\s+without", Pattern.CASE_INSENSITIVE),
        Pattern.compile("stream\\s+ended\\s+before", Pattern.CASE_INSENSITIVE),
        Pattern.compile("ResourceExhausted"),
        Pattern.compile("temporarily\\s+unavailable", Pattern.CASE_INSENSITIVE),
        Pattern.compile("service\\s+unavailable", Pattern.CASE_INSENSITIVE),
        Pattern.compile("internal\\s+server\\s+error", Pattern.CASE_INSENSITIVE)
    );

    /**
     * @param errorMessage LLM 响应中的错误文本，可为 null
     * @return true 表示该错误值得重试
     */
    public boolean isRetryable(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return false;
        }

        for (Pattern p : NON_RETRYABLE) {
            if (p.matcher(errorMessage).find()) {
                log.debug("Error classified as non-retryable (matched {}): {}",
                    p.pattern(), abbreviate(errorMessage));
                return false;
            }
        }

        for (Pattern p : RETRYABLE) {
            if (p.matcher(errorMessage).find()) {
                log.debug("Error classified as retryable (matched {}): {}",
                    p.pattern(), abbreviate(errorMessage));
                return true;
            }
        }

        // 未匹配任何已知模式 → 保守地不重试。
        // 未知错误可能是请求本身有问题（如上下文超限、参数非法），重试只会重复失败。
        log.debug("Error matched no known pattern, treating as non-retryable: {}",
            abbreviate(errorMessage));
        return false;
    }

    /** 判定异常是否值得重试（用于 onErrorResume 场景）。 */
    public boolean isRetryable(Throwable error) {
        if (error == null) {
            return false;
        }
        // 逐层检查异常链：WebClientRequestException 常把 ECONNRESET 包在 cause 里
        Throwable cursor = error;
        int depth = 0;
        while (cursor != null && depth < 5) {
            if (isRetryable(cursor.getMessage())) {
                return true;
            }
            String className = cursor.getClass().getSimpleName();
            if (className.contains("Timeout") || className.contains("Connect")) {
                return true;
            }
            cursor = cursor.getCause();
            depth++;
        }
        return false;
    }

    private String abbreviate(String s) {
        return s.length() <= 160 ? s : s.substring(0, 160) + "...";
    }
}
