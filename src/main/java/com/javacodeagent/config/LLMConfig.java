package com.javacodeagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "llm")
public class LLMConfig {
    private String provider;
    private String apiKey;
    private String model;
    private int maxTokens;
    private double temperature;
    private String endpoint;
    private String systemPrompt;
    private boolean thinkingEnabled;

    public static final int DEFAULT_THINKING_BUDGET_TOKENS = 8000;

    /** Extended thinking token budget (only effective when thinkingEnabled=true). */
    private int thinkingBudgetTokens = DEFAULT_THINKING_BUDGET_TOKENS;
}
