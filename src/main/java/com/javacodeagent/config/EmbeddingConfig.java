package com.javacodeagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 记忆 Embedding 配置（绑定 {@code memory.embedding.*}）。
 *
 * <pre>
 * memory:
 *   embedding:
 *     enabled: true
 *     provider: openai          # openai / qwen / ollama / custom
 *     api-key: ${LLM_API_KEY:}  # 与 llm.api-key 共享同一个 key 即可
 *     model: text-embedding-3-small
 *     endpoint: https://api.openai.com/v1
 *     dimensions: 1536          # 模型输出维度（text-embedding-3-small=1536）
 *     top-k: 5                  # 语义检索默认返回条数
 *     similarity-threshold: 0.5 # 余弦相似度下限（低于此值不返回）
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "memory.embedding")
public class EmbeddingConfig {

    /** 是否启用向量检索；false 时退化为关键词搜索，零开销。 */
    private boolean enabled = false;

    /**
     * Embedding 模型服务商标识（仅用于日志/注释，实际端点由 endpoint 决定）。
     * 常用值：openai / qwen / ollama / custom
     */
    private String provider = "openai";

    /** API Key（留空则不发送 Authorization 头，适用于本地 Ollama）。 */
    private String apiKey = "";

    /** Embedding 模型名称。 */
    private String model = "text-embedding-3-small";

    /**
     * API 基础 URL（不含路径，如 {@code https://api.openai.com/v1}）。
     * 工具类方法会自动追加 {@code /embeddings}。
     */
    private String endpoint = "https://api.openai.com/v1";

    /** 向量维度，必须与所选模型一致。 */
    private int dimensions = 1536;

    /** 语义检索默认返回的最大条数。 */
    private int topK = 5;

    /**
     * 余弦相似度阈值（0~1）：低于此值的结果不返回。
     * 推荐值 0.5；要求严格时可调至 0.7。
     */
    private double similarityThreshold = 0.5;
}
