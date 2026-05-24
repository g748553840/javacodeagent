package com.javacodeagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "memory")
public class MemoryConfig {
    private boolean enabled = true;
    private String location = "${user.home}/.java-code-agent/memory";
    private String indexFile = "MEMORY.md";
}
