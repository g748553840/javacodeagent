package com.javacodeagent.core.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javacodeagent.config.EmbeddingConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 Embedding 客户端。
 * <p>
 * 调用 {@code POST {endpoint}/embeddings}，兼容：
 * <ul>
 *   <li>OpenAI（text-embedding-3-small / text-embedding-ada-002）</li>
 *   <li>Azure OpenAI</li>
 *   <li>阿里云通义（text-embedding-v2 / v3）</li>
 *   <li>DeepSeek（deepseek-embedding）</li>
 *   <li>Ollama（nomic-embed-text 等）</li>
 *   <li>任何遵循 OpenAI Embeddings API 规范的自托管服务</li>
 * </ul>
 * <p>
 * 仅当 {@code memory.embedding.enabled=true} 时由 {@link EmbeddingClientConfig} 注册。
 */
@Slf4j
public class OpenAICompatibleEmbeddingClient implements EmbeddingClient {

    private final WebClient webClient;
    private final String model;
    private final ObjectMapper objectMapper;

    public OpenAICompatibleEmbeddingClient(EmbeddingConfig config, WebClient.Builder webClientBuilder) {
        this.model = config.getModel();
        this.objectMapper = new ObjectMapper();

        WebClient.Builder builder = webClientBuilder
                .baseUrl(config.getEndpoint())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey());
        }
        this.webClient = builder.build();

        log.info("EmbeddingClient initialized: provider={}, model={}, endpoint={}",
                config.getProvider(), config.getModel(), config.getEndpoint());
    }

    @Override
    public Mono<float[]> embed(String text) {
        if (text == null || text.isBlank()) {
            return Mono.just(new float[0]);
        }

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "input", text
        );

        return webClient.post()
                .uri("/embeddings")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parseEmbedding)
                .doOnError(e -> log.warn("Embedding API call failed for text (first 80 chars): '{}…': {}",
                        text.length() > 80 ? text.substring(0, 80) : text, e.getMessage()));
    }

    /**
     * 解析 OpenAI Embeddings API 响应格式：
     * <pre>
     * {
     *   "data": [
     *     { "embedding": [0.123, -0.456, ...], "index": 0, "object": "embedding" }
     *   ],
     *   "model": "text-embedding-3-small",
     *   "usage": { ... }
     * }
     * </pre>
     */
    private float[] parseEmbedding(String responseJson) {
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode dataArray = root.path("data");
            if (dataArray.isMissingNode() || dataArray.isEmpty()) {
                log.warn("Embedding API response contains no data: {}", responseJson);
                return new float[0];
            }
            JsonNode embeddingArray = dataArray.get(0).path("embedding");
            float[] vector = new float[embeddingArray.size()];
            for (int i = 0; i < embeddingArray.size(); i++) {
                vector[i] = (float) embeddingArray.get(i).asDouble();
            }
            return vector;
        } catch (Exception e) {
            log.error("Failed to parse embedding response: {}", responseJson, e);
            return new float[0];
        }
    }
}
