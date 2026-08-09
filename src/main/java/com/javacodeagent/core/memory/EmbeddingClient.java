package com.javacodeagent.core.memory;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 文本 Embedding 接口。
 * <p>
 * 默认实现（{@link OpenAICompatibleEmbeddingClient}）调用 OpenAI 兼容的
 * {@code POST /v1/embeddings} 端点，可对接任何支持该接口的模型服务
 * （OpenAI / Azure / Qwen / DeepSeek / Ollama 等）。
 * <p>
 * 当 {@code memory.embedding.enabled=false} 时不创建任何实现 Bean，
 * {@link MemoryService} 自动降级为关键词搜索。
 */
public interface EmbeddingClient {

    /**
     * 对单个文本生成 embedding 向量。
     *
     * @param text 输入文本（建议 ≤ 8192 token）
     * @return Mono 包含 float 数组（维度取决于模型，如 text-embedding-3-small = 1536）
     */
    Mono<float[]> embed(String text);

    /**
     * 批量 embedding（内部循环调用 {@link #embed}，子类可按需覆盖以利用批量接口）。
     *
     * @param texts 输入文本列表
     * @return 顺序对应的 embedding 向量列表
     */
    default Mono<List<float[]>> embedAll(List<String> texts) {
        return reactor.core.publisher.Flux.fromIterable(texts)
                .concatMap(this::embed)
                .collectList();
    }
}
