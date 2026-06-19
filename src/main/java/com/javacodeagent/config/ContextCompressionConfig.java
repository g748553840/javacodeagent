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
 *     threshold: 40      # 消息数超过此值触发压缩
 *     keep-recent: 10    # 压缩后保留最近 N 条消息
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "context.compression")
public class ContextCompressionConfig {
    private boolean enabled = true;
    private int threshold = 40;
    private int keepRecent = 10;
}
