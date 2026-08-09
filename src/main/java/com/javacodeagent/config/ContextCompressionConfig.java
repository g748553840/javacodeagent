package com.javacodeagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 上下文压缩配置（绑定 application.yml 中的 context.compression.* 节点）。
 *
 * <pre>
 * context:
 *   compression:
 *     enabled: true
 *     threshold: 40             # 消息数超过此值触发压缩（条数触发，保留兼容）
 *     keep-recent: 10           # 条数模式下保留最近 N 条消息
 *     token-based: true         # 启用基于 token 预算的触发与切点选择
 *     max-tokens: 100000        # 上下文窗口预算
 *     reserve-tokens: 16384     # 为响应预留的 token（超过 max-tokens - reserve 即触发）
 *     keep-recent-tokens: 20000 # token 模式下尾部保留的 token 预算
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "context.compression")
public class ContextCompressionConfig {
    private boolean enabled = true;
    private int threshold = 40;
    private int keepRecent = 10;

    /**
     * 是否启用基于 token 的压缩策略。
     *
     * <p>true 时使用 token 预算判定触发时机，并用切点算法保证不切开
     * tool_use / tool_result 配对；false 时退回按消息条数的旧行为。
     */
    private boolean tokenBased = true;

    /** 上下文窗口总预算。 */
    private int maxTokens = 100_000;

    /** 为模型响应预留的 token 数，对齐 pi 的 reserveTokens 默认值。 */
    private int reserveTokens = 16_384;

    /** 尾部保留的 token 预算，对齐 pi 的 keepRecentTokens 默认值。 */
    private int keepRecentTokens = 20_000;
}
