package com.javacodeagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "agent")
public class AgentConfig {
    /** Agent 循环最大工具调用深度，超出后返回错误。默认 50。 */
    private int maxToolCallDepth = 50;
}
