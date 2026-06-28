package com.javacodeagent.core.data;

import com.javacodeagent.core.data.model.Nl2SqlResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NL2SQL 历史查询缓存服务。
 *
 * <p>对相似问题（标准化后相同）复用已生成的 SQL，避免重复调用 LLM。
 *
 * <p>实现策略：
 * <ul>
 *   <li>LRU（Least Recently Used）驱逐 — 最大 {@value #MAX_SIZE} 条</li>
 *   <li>TTL — 条目超过 {@value #TTL_MILLIS} 毫秒后视为过期，下次访问时懒删除</li>
 *   <li>线程安全 — 使用 {@code ConcurrentHashMap} 的原子 {@code compute} 操作，
 *       避免 get-check-remove 的 TOCTOU 竞争；LRU 排序由写时同步的 LinkedHashMap 维护</li>
 * </ul>
 *
 * <p>key 标准化规则：trim → lowercase → 合并连续空白 → 移除中英文标点 → 去首尾空格。
 * 相同语义但标点/大小写不同的问题将命中同一缓存条目。
 */
@Slf4j
@Service
public class SqlCacheService {

    static final int MAX_SIZE = 500;
    static final long TTL_MILLIS = 60 * 60 * 1000L; // 1 hour

    private record CacheEntry(Nl2SqlResult result, long createdAt) {}

    // ConcurrentHashMap for atomic get-check-remove (avoids TOCTOU race of Collections.synchronizedMap)
    private final ConcurrentHashMap<String, CacheEntry> store = new ConcurrentHashMap<>(64);
    // LRU order tracking — access under synchronized(lruOrder)
    private final LinkedHashMap<String, Boolean> lruOrder = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            if (size() > MAX_SIZE) {
                store.remove(eldest.getKey());
                return true;
            }
            return false;
        }
    };

    /**
     * Look up a cached SQL result for the given question.
     *
     * @return the cached {@link Nl2SqlResult} if present and not expired, otherwise empty
     */
    public Optional<Nl2SqlResult> get(String question) {
        String key = normalize(question);
        // Atomically check-and-remove expired entries via compute
        CacheEntry[] holder = new CacheEntry[1];
        store.computeIfPresent(key, (k, entry) -> {
            if (System.currentTimeMillis() - entry.createdAt() > TTL_MILLIS) {
                log.debug("SQL cache expired for key: {}", k);
                synchronized (lruOrder) { lruOrder.remove(k); }
                return null; // removes from ConcurrentHashMap atomically
            }
            holder[0] = entry;
            return entry;
        });
        if (holder[0] != null) {
            synchronized (lruOrder) { lruOrder.get(key); } // mark as recently used
            log.debug("SQL cache hit for: {}", key);
            return Optional.of(holder[0].result());
        }
        return Optional.empty();
    }

    /**
     * Store a SQL result in the cache for the given question.
     */
    public void put(String question, Nl2SqlResult result) {
        String key = normalize(question);
        store.put(key, new CacheEntry(result, System.currentTimeMillis()));
        synchronized (lruOrder) { lruOrder.put(key, Boolean.TRUE); }
        log.debug("SQL cache stored for key: {} (cache size={})", key, store.size());
    }

    /** Return current number of entries in the cache. */
    public int size() {
        return store.size();
    }

    /** Normalize the question to a stable cache key. */
    String normalize(String question) {
        if (question == null) return "";
        return question
            .trim()
            .toLowerCase()
            .replaceAll("[\\s]+", " ")           // normalize whitespace
            .replaceAll("[，。？！、,?!.;；：:]", " ") // remove punctuation
            .replaceAll("\\s+", " ")              // collapse again after punctuation removal
            .strip();
    }
}
