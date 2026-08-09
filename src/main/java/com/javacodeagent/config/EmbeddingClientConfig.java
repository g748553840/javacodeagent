package com.javacodeagent.config;

import com.javacodeagent.core.memory.EmbeddingClient;
import com.javacodeagent.core.memory.OpenAICompatibleEmbeddingClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Embedding Bean 注册配置。
 *
 * 仅当 {@code memory.embedding.enabled=true} 时注册 {@link EmbeddingClient} Bean，
 * 否则整个 Embedding 路径不启动，{@link com.javacodeagent.core.memory.MemoryService}
 * 自动降级为关键词搜索。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class EmbeddingClientConfig {

    private final EmbeddingConfig embeddingConfig;

    /**
     * 注册 OpenAI 兼容 Embedding 客户端。
     * 条件：{@code memory.embedding.enabled=true}。
     */
    @Bean
    @ConditionalOnProperty(prefix = "memory.embedding", name = "enabled", havingValue = "true")
    public EmbeddingClient embeddingClient(WebClient.Builder webClientBuilder) {
        log.info("Embedding enabled: provider={}, model={}",
                embeddingConfig.getProvider(), embeddingConfig.getModel());
        return new OpenAICompatibleEmbeddingClient(embeddingConfig, webClientBuilder);
    }
}
