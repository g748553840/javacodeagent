package com.javacodeagent.core.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MemoryEmbeddingStore} 单元测试。
 * 不依赖 Spring 上下文，直接测试余弦相似度计算和向量检索逻辑。
 */
class MemoryEmbeddingStoreTest {

    private MemoryEmbeddingStore store;

    @BeforeEach
    void setUp() {
        store = new MemoryEmbeddingStore();
    }

    // ---- put / remove / size ----

    @Test
    void put_and_size() {
        store.put("alice", "m1", new float[]{1f, 0f, 0f});
        store.put("alice", "m2", new float[]{0f, 1f, 0f});
        assertThat(store.size("alice")).isEqualTo(2);
    }

    @Test
    void put_emptyVector_ignored() {
        store.put("alice", "m1", new float[]{});
        assertThat(store.size("alice")).isEqualTo(0);
    }

    @Test
    void remove_decreasesSize() {
        store.put("alice", "m1", new float[]{1f, 0f});
        store.remove("alice", "m1");
        assertThat(store.size("alice")).isEqualTo(0);
    }

    @Test
    void size_unknownUser_returnsZero() {
        assertThat(store.size("nobody")).isEqualTo(0);
    }

    // ---- findTopK：余弦相似度 ----

    @Test
    void findTopK_exactMatch_returnsScore1() {
        float[] vec = {0.6f, 0.8f, 0f};
        store.put("alice", "m1", vec);

        List<MemoryEmbeddingStore.ScoredMemoryId> results =
                store.findTopK("alice", vec, 5, 0.0);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).memoryId()).isEqualTo("m1");
        // 完全相同向量余弦相似度 = 1.0（允许浮点误差）
        assertThat(results.get(0).score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    void findTopK_orthogonalVectors_scoreZero() {
        store.put("alice", "m1", new float[]{1f, 0f});
        store.put("alice", "m2", new float[]{0f, 1f});

        List<MemoryEmbeddingStore.ScoredMemoryId> results =
                store.findTopK("alice", new float[]{1f, 0f}, 5, 0.0);

        // m1 相似度 = 1.0，m2 相似度 = 0.0，两者都应返回（threshold=0）
        assertThat(results).hasSize(2);
        assertThat(results.get(0).memoryId()).isEqualTo("m1");
        assertThat(results.get(0).score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(results.get(1).score()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    void findTopK_thresholdFiltersLow() {
        store.put("alice", "high", new float[]{1f, 0f});   // score ~1.0
        store.put("alice", "low",  new float[]{0f, 1f});   // score ~0.0

        List<MemoryEmbeddingStore.ScoredMemoryId> results =
                store.findTopK("alice", new float[]{1f, 0f}, 5, 0.5);

        // 低相似度的 "low" 被阈值过滤
        assertThat(results).hasSize(1);
        assertThat(results.get(0).memoryId()).isEqualTo("high");
    }

    @Test
    void findTopK_respects_topK_limit() {
        store.put("alice", "m1", new float[]{1f, 0.1f});
        store.put("alice", "m2", new float[]{1f, 0.2f});
        store.put("alice", "m3", new float[]{1f, 0.3f});

        List<MemoryEmbeddingStore.ScoredMemoryId> results =
                store.findTopK("alice", new float[]{1f, 0.2f}, 2, 0.0);

        assertThat(results).hasSize(2);
    }

    @Test
    void findTopK_descending_order() {
        store.put("alice", "near",   new float[]{1f, 0f});
        store.put("alice", "middle", new float[]{0.7f, 0.7f});
        store.put("alice", "far",    new float[]{0f, 1f});

        List<MemoryEmbeddingStore.ScoredMemoryId> results =
                store.findTopK("alice", new float[]{1f, 0f}, 5, 0.0);

        // 结果按相似度倒序
        for (int i = 1; i < results.size(); i++) {
            assertThat(results.get(i - 1).score()).isGreaterThanOrEqualTo(results.get(i).score());
        }
    }

    @Test
    void findTopK_emptyStore_returnsEmpty() {
        List<MemoryEmbeddingStore.ScoredMemoryId> results =
                store.findTopK("alice", new float[]{1f, 0f}, 5, 0.0);
        assertThat(results).isEmpty();
    }

    @Test
    void findTopK_multiUser_isolated() {
        store.put("alice", "a1", new float[]{1f, 0f});
        store.put("bob",   "b1", new float[]{0f, 1f});

        // alice 只能检索到自己的记忆
        List<MemoryEmbeddingStore.ScoredMemoryId> aliceResults =
                store.findTopK("alice", new float[]{1f, 0f}, 5, 0.0);

        assertThat(aliceResults).hasSize(1);
        assertThat(aliceResults.get(0).memoryId()).isEqualTo("a1");
    }

    @Test
    void findTopK_zeroVector_returnsZeroScore() {
        store.put("alice", "m1", new float[]{1f, 0f});

        // 零向量与任何向量的余弦相似度 = 0（防除零保护）
        List<MemoryEmbeddingStore.ScoredMemoryId> results =
                store.findTopK("alice", new float[]{0f, 0f}, 5, 0.0);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-5));
    }
}
