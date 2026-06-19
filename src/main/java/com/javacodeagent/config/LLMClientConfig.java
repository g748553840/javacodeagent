package com.javacodeagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javacodeagent.core.llm.AnthropicLLMClient;
import com.javacodeagent.core.llm.LLMClient;
import com.javacodeagent.core.llm.OpenAILLMClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Set;

/**
 * LLM 客户端工厂。
 * 根据 llm.provider 配置选择 AnthropicLLMClient 或 OpenAILLMClient，
 * 注册为唯一的 LLMClient Bean，供 ConversationManager 等组件注入。
 */
@Slf4j
@Configuration
public class LLMClientConfig {

    @Bean
    public LLMClient llmClient(WebClient webClient, LLMConfig config, ObjectMapper objectMapper) {
        String provider = config.getProvider() != null ? config.getProvider().toLowerCase() : "anthropic";
        log.info("Initializing LLM client for provider: {}", provider);

        if ("anthropic".equals(provider)) {
            return new AnthropicLLMClient(webClient, config, objectMapper);
        } else {
            Set<String> knownProviders = Set.of(
                "openai", "deepseek", "glm", "chatglm", "zhipu", "qwen", "dashscope", "custom");
            if (!knownProviders.contains(provider)) {
                log.warn("Unknown LLM provider '{}', falling back to OpenAI-compatible client. " +
                    "Known providers: {}", provider, knownProviders);
            } else {
                log.info("Using OpenAI-compatible client (provider={}), endpoint will be resolved from config",
                    provider);
            }
            return new OpenAILLMClient(webClient, config, objectMapper);
        }
    }
}
