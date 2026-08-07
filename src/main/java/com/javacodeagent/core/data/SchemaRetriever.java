package com.javacodeagent.core.data;

import com.javacodeagent.core.memory.EmbeddingClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Schema 检索服务。
 *
 * <p>支持两种检索模式：
 * <ul>
 *   <li><b>关键词匹配</b>（默认）：对问题分词后与表名做词汇重叠评分，取 top-K。</li>
 *   <li><b>向量语义检索</b>（{@code memory.embedding.enabled=true} 时启用）：
 *       调用 {@link EmbeddingClient} 为表名建立向量索引，
 *       对查询向量做余弦相似度排序，准确率更高，对中英混合问题效果尤为明显。</li>
 * </ul>
 *
 * <p>多数据源支持：通过 {@link #retrieve(String, DataSourceConnector, String)} 重载，
 * 接收显式的连接器和数据源 ID，不依赖注入的默认连接器。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaRetriever {

    private final DataSourceConnector defaultConnector;

    /** Embedding 客户端（可选，未启用时为 null）。 */
    @Autowired(required = false)
    private EmbeddingClient embeddingClient;

    // ── 向量索引 ─────────────────────────────────────────────────────────────

    /**
     * 表 embedding 缓存：key = "{dataSourceId}::{tableName}"，value = float[]。
     * 多数据源各自独立索引，互不干扰。
     */
    private final ConcurrentHashMap<String, float[]> tableEmbeddings = new ConcurrentHashMap<>();

    /**
     * 索引构建状态锁：key = dataSourceId，value = 占位对象。
     * <p>
     * 使用 {@link ConcurrentHashMap#computeIfAbsent} 保证对同一 dataSourceId
     * 的索引构建只由一个线程执行，消除原先的 TOCTOU 竞争条件。
     */
    private final ConcurrentHashMap<String, Object> indexBuilt = new ConcurrentHashMap<>();
    private static final Object BUILT = new Object();

    // ── 配置常量 ──────────────────────────────────────────────────────────────

    private static final String DEFAULT_DS_ID = "default";
    private static final int MAX_FULL_SCHEMA_TABLES = 10;
    private static final int TOP_K_TABLES = 5;

    /** 关键词最小有效长度（码点数），过短词汇过于泛化。 */
    private static final int KEYWORD_MIN_CODEPOINTS = 2;

    /**
     * 向量相似度阈值（比记忆检索低，因为表名语义通常比记忆内容更简短、离散）。
     * 若所有表的相似度均低于此值，自动降级为关键词匹配。
     */
    private static final double SCHEMA_EMBEDDING_THRESHOLD = 0.25;

    /** Embedding API 单次调用超时。 */
    private static final Duration EMBEDDING_TIMEOUT = Duration.ofSeconds(30);

    // ── 公共 API ──────────────────────────────────────────────────────────────

    /**
     * 检索与问题相关的 Schema（使用默认数据源）。
     */
    public Mono<String> retrieve(String question) {
        return retrieve(question, defaultConnector, DEFAULT_DS_ID);
    }

    /**
     * 检索与问题相关的 Schema（指定数据源）。
     *
     * @param question     自然语言问题
     * @param connector    数据源连接器
     * @param dataSourceId 数据源标识（用于分隔向量索引缓存）
     * @return DDL + 样例行文本（供 LLM NL2SQL 使用）
     */
    public Mono<String> retrieve(String question, DataSourceConnector connector, String dataSourceId) {
        return Mono.fromCallable(() -> {
            List<String> allTables = connector.listTables();
            if (allTables.isEmpty()) {
                return "-- No tables found in database: " + connector.getDatabaseName();
            }

            List<String> selected;
            if (allTables.size() <= MAX_FULL_SCHEMA_TABLES) {
                // 表少时全量传入
                selected = allTables;
            } else if (embeddingClient != null) {
                // 向量语义检索
                selected = selectByEmbedding(question, allTables, connector, dataSourceId);
            } else {
                // 关键词评分
                selected = selectByKeyword(question, allTables);
            }

            log.debug("Schema retrieval [{}]: {}/{} tables selected (mode={}) for question: {}",
                dataSourceId, selected.size(), allTables.size(),
                embeddingClient != null ? "vector" : "keyword", question);

            return connector.getTableInfo(selected);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 清除指定数据源的向量索引缓存（数据源表结构变更后调用）。
     */
    public void invalidateIndex(String dataSourceId) {
        String prefix = dataSourceId + "::";
        tableEmbeddings.keySet().removeIf(k -> k.startsWith(prefix));
        indexBuilt.remove(dataSourceId);
        log.info("Schema embedding index invalidated for datasource: {}", dataSourceId);
    }

    // ── 私有方法 ──────────────────────────────────────────────────────────────

    /**
     * 向量语义检索：
     * <ol>
     *   <li>按需构建该数据源的表向量索引（首次调用时同步构建，通过 computeIfAbsent 保证只构建一次）</li>
     *   <li>对查询文本生成 embedding</li>
     *   <li>按余弦相似度排序，取 top-K</li>
     *   <li>若向量检索无结果，降级为关键词匹配</li>
     * </ol>
     */
    private List<String> selectByEmbedding(String question, List<String> tables,
                                            DataSourceConnector connector, String dataSourceId) {
        // computeIfAbsent 保证对同一 dataSourceId 索引只构建一次，消除 TOCTOU 竞争
        indexBuilt.computeIfAbsent(dataSourceId, id -> {
            buildIndexBlocking(tables, connector, id);
            return BUILT;
        });

        // 生成查询向量
        float[] queryVec;
        try {
            queryVec = embeddingClient.embed(question).block(EMBEDDING_TIMEOUT);
        } catch (Exception e) {
            log.warn("Failed to embed schema retrieval query, falling back to keyword: {}", e.getMessage());
            return selectByKeyword(question, tables);
        }
        if (queryVec == null || queryVec.length == 0) {
            return selectByKeyword(question, tables);
        }

        // 余弦相似度排序
        String prefix = dataSourceId + "::";
        List<String> topTables = tableEmbeddings.entrySet().stream()
            .filter(e -> e.getKey().startsWith(prefix))
            .map(e -> Map.entry(
                e.getKey().substring(prefix.length()),
                cosineSimilarity(queryVec, e.getValue())
            ))
            .filter(e -> e.getValue() >= SCHEMA_EMBEDDING_THRESHOLD)
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(TOP_K_TABLES)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());

        if (topTables.isEmpty()) {
            log.debug("Schema vector search returned 0 results (threshold={}), falling back to keyword",
                SCHEMA_EMBEDDING_THRESHOLD);
            return selectByKeyword(question, tables);
        }

        return topTables;
    }

    /**
     * 同步构建表向量索引（调用方需在 boundedElastic 线程上）。
     *
     * <p>Embedding 文本 = 表名（snake_case → 空格分隔）+ 列名摘要（可选）
     */
    private void buildIndexBlocking(List<String> tables, DataSourceConnector connector,
                                    String dataSourceId) {
        log.info("Building schema embedding index for datasource '{}': {} tables", dataSourceId, tables.size());
        int indexed = 0;
        for (String table : tables) {
            String embeddingText = buildTableEmbeddingText(table, connector);
            try {
                float[] vec = embeddingClient.embed(embeddingText).block(EMBEDDING_TIMEOUT);
                if (vec != null && vec.length > 0) {
                    tableEmbeddings.put(dataSourceId + "::" + table, vec);
                    indexed++;
                }
            } catch (Exception e) {
                log.warn("Failed to embed schema for table '{}': {}", table, e.getMessage());
            }
        }
        log.info("Schema index ready for '{}': {}/{} tables indexed", dataSourceId, indexed, tables.size());
    }

    /**
     * 构建用于 embedding 的表描述文本。
     *
     * <p>格式："{table name with spaces}: {col1} {col2} ..."
     */
    private String buildTableEmbeddingText(String tableName, DataSourceConnector connector) {
        // 表名：snake_case → 英文单词（提升语义识别效果）
        String readableName = tableName.replace("_", " ").replace("-", " ").toLowerCase();
        StringBuilder sb = new StringBuilder(readableName).append(": ");
        try {
            // 获取列名（从 DDL 中解析，避免额外的 DB 查询）
            String ddl = connector.getTableInfo(List.of(tableName));
            ddl.lines()
               .filter(l -> l.trim().startsWith("\"") || l.trim().startsWith("`"))
               .map(l -> l.trim().replaceAll("[\"`,]", "").split("\\s+")[0])
               .filter(col -> !col.isBlank())
               .limit(20)
               .forEach(col -> sb.append(col.replace("_", " ")).append(" "));
        } catch (Exception e) {
            log.debug("Could not extract columns for table '{}': {}", tableName, e.getMessage());
        }
        return sb.toString().trim();
    }

    /** 关键词评分（原有逻辑，作为向量检索降级方案）。 */
    private List<String> selectByKeyword(String question, List<String> tables) {
        String lower = question.toLowerCase();
        Set<String> keywords = new HashSet<>(Arrays.asList(lower.split("[\\s,，。？！、\\-_]+")));
        keywords.removeIf(k -> k.codePointCount(0, k.length()) < KEYWORD_MIN_CODEPOINTS);

        return tables.stream()
            .sorted(Comparator.comparingInt((String t) -> -scoreTable(t.toLowerCase(), keywords)))
            .limit(TOP_K_TABLES)
            .collect(Collectors.toList());
    }

    private int scoreTable(String tableName, Set<String> keywords) {
        return (int) keywords.stream().filter(tableName::contains).count();
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot   += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom < 1e-9 ? 0.0 : dot / denom;
    }
}
