package com.javacodeagent.core.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存向量存储。
 * <p>
 * 存储结构：{@code userId → memoryId → float[]}（双层 ConcurrentHashMap）。<br>
 * 检索时对目标用户的全量向量做线性扫描余弦相似度计算，返回按分数倒序的 top-K 结果。
 * 对于常见记忆规模（同一用户 ≤ 2000 条、向量维度 1536），单次检索耗时约 1-5ms。
 * <p>
 * 仅当 {@code memory.embedding.enabled=true} 时由 Spring 注册。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "memory.embedding", name = "enabled", havingValue = "true")
public class MemoryEmbeddingStore {

    /**
     * 向量存储：userId → (memoryId → embedding 向量)
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, float[]>> store =
            new ConcurrentHashMap<>();

    /**
     * 存入或更新指定记忆的向量。
     *
     * @param userId    用户 ID
     * @param memoryId  记忆 ID
     * @param embedding 向量（空数组时跳过存储）
     */
    public void put(String userId, String memoryId, float[] embedding) {
        if (embedding == null || embedding.length == 0) return;
        store.computeIfAbsent(userId, u -> new ConcurrentHashMap<>())
             .put(memoryId, embedding);
    }

    /**
     * 删除指定记忆的向量。
     */
    public void remove(String userId, String memoryId) {
        ConcurrentHashMap<String, float[]> userStore = store.get(userId);
        if (userStore != null) {
            userStore.remove(memoryId);
        }
    }

    /**
     * 语义检索：返回余弦相似度最高的 top-K 记忆 ID（按分数倒序）。
     *
     * @param userId         用户 ID
     * @param queryEmbedding 查询向量
     * @param topK           最多返回条数
     * @param threshold      余弦相似度阈值（低于此值不返回）
     * @return 按相似度倒序排列的 {@link ScoredMemoryId} 列表
     */
    public List<ScoredMemoryId> findTopK(String userId, float[] queryEmbedding,
                                          int topK, double threshold) {
        ConcurrentHashMap<String, float[]> userStore = store.get(userId);
        if (userStore == null || userStore.isEmpty()) {
            return List.of();
        }

        List<ScoredMemoryId> results = new ArrayList<>(userStore.size());
        for (Map.Entry<String, float[]> entry : userStore.entrySet()) {
            double score = cosineSimilarity(queryEmbedding, entry.getValue());
            if (score >= threshold) {
                results.add(new ScoredMemoryId(entry.getKey(), score));
            }
        }

        results.sort(Comparator.comparingDouble(ScoredMemoryId::score).reversed());
        return results.size() <= topK ? results : results.subList(0, topK);
    }

    /**
     * 返回指定用户当前已存入的向量数量（用于监控/调试）。
     */
    public int size(String userId) {
        ConcurrentHashMap<String, float[]> userStore = store.get(userId);
        return userStore == null ? 0 : userStore.size();
    }

    /**
     * 余弦相似度：{@code dot(a, b) / (‖a‖ × ‖b‖)}。
     * <p>
     * 返回值范围 [-1, 1]；完全相同的向量返回 1.0，完全无关返回约 0.0。
     * 当任一向量为零向量时返回 0.0（防除零）。
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot   += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        // 分母小于极小值时视为零向量，返回 0
        return denom < 1e-9 ? 0.0 : dot / denom;
    }

    /**
     * 语义搜索结果条目（包含 memoryId 和余弦相似度分数）。
     *
     * @param memoryId 记忆 ID
     * @param score    余弦相似度（0~1）
     */
    public record ScoredMemoryId(String memoryId, double score) {}
}
