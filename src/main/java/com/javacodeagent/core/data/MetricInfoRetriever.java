package com.javacodeagent.core.data;

import com.javacodeagent.core.data.model.DataQueryResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 指标元数据检索服务。
 *
 * <p>功能：
 * <ul>
 *   <li>维护指标注册表（内存 + 可选从 DB 加载），支持按名称/分类查询</li>
 *   <li>为指标查询组装 SQL，获取时序数据（当前值、历史趋势、分组维度）</li>
 *   <li>为 {@link com.javacodeagent.core.data.agent.DataAnalysisAgent} 归因链路
 *       提供结构化输入（仿 DB-GPT MetricInfoRetriever → AnomalyDetector → VolatilityAnalysis → Report）</li>
 * </ul>
 *
 * <h3>指标体系集成路径：</h3>
 * <pre>
 * MetricInfoRetriever.getMetricInfo(name)
 *     │  → 查询指标当前值 + 历史趋势 + 候选分析维度
 *     ▼
 * MetricAnalysisContext（结构化上下文）
 *     │
 *     ├── AnomalyDetectorAgent（检测当前值是否异常）
 *     ├── VolatilityAnalysisAgent（计算 CV / 趋势方向）
 *     └── ReportGenerationAgent（聚合 → MetricAnalysisReport）
 * </pre>
 */
@Slf4j
@Service
public class MetricInfoRetriever {

    private final DataSourceConnector connector;
    private final SqlValidator sqlValidator;
    private final SqlExecutor sqlExecutor;

    /** 运行时注册的指标定义（programmatic / REST 注册）。 */
    private final ConcurrentHashMap<String, MetricDefinition> metricRegistry = new ConcurrentHashMap<>();

    public MetricInfoRetriever(DataSourceConnector connector,
                               SqlValidator sqlValidator,
                               SqlExecutor sqlExecutor) {
        this.connector = connector;
        this.sqlValidator = sqlValidator;
        this.sqlExecutor = sqlExecutor;
    }

    // ── SQL 安全校验 ──────────────────────────────────────────────────────────

    /** SQL 标识符合法字符集：仅允许字母、数字、下划线，且不以数字开头。 */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /** 允许的聚合函数白名单（大写）。 */
    private static final Set<String> ALLOWED_AGGREGATIONS =
        Set.of("SUM", "COUNT", "AVG", "MIN", "MAX");

    /**
     * 校验 SQL 标识符（表名 / 列名）是否安全。
     * 阻止 SQL 注入：禁止特殊字符、空白、引号、括号等。
     */
    private static void validateIdentifier(String fieldName, String value) {
        if (value == null || value.isBlank()) return;
        if (!IDENTIFIER_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "Invalid SQL identifier for '" + fieldName + "': '" + value +
                "'. Only letters, digits, and underscores are allowed.");
        }
    }

    /**
     * 校验并规范化聚合函数名。
     * 不在白名单中时抛出异常（而非静默回退），让调用方显式选择合法值。
     */
    private static String validateAggregation(String agg) {
        if (agg == null || agg.isBlank()) return "SUM";
        String upper = agg.trim().toUpperCase();
        if (!ALLOWED_AGGREGATIONS.contains(upper)) {
            throw new IllegalArgumentException(
                "Invalid aggregation function: '" + agg + "'. Allowed: " + ALLOWED_AGGREGATIONS);
        }
        return upper;
    }

    /**
     * 按方言返回标识符引号字符。
     * MySQL 使用反引号；PostgreSQL / H2 / ANSI SQL 使用双引号。
     */
    private String q(String identifier) {
        if ("mysql".equalsIgnoreCase(connector.getDialect())) {
            return "`" + identifier + "`";
        }
        return "\"" + identifier + "\"";
    }

    // ── 指标注册 ──────────────────────────────────────────────────────────────

    /**
     * 注册指标定义。
     * 在注册时校验 table/column/aggregation 字段的合法性，避免 SQL 注入。
     *
     * @param def 指标定义（包含名称、SQL、维度配置）
     */
    public void register(MetricDefinition def) {
        validateIdentifier("table", def.table());
        validateIdentifier("valueColumn", def.valueColumn());
        validateIdentifier("timeColumn", def.timeColumn());
        if (def.aggregation() != null) validateAggregation(def.aggregation());
        metricRegistry.put(def.name(), def);
        log.info("Registered metric: {} (table={}, valueCol={})", def.name(), def.table(), def.valueColumn());
    }

    /**
     * 注销指标。
     */
    public void unregister(String name) {
        metricRegistry.remove(name);
    }

    /**
     * 列出所有已注册指标定义。
     */
    public List<MetricDefinition> listMetrics() {
        return List.copyOf(metricRegistry.values());
    }

    /**
     * 查询指标是否已注册。
     */
    public boolean contains(String name) {
        return metricRegistry.containsKey(name);
    }

    // ── 指标数据检索 ──────────────────────────────────────────────────────────

    /**
     * 获取指标的完整分析上下文（当前聚合值 + 历史趋势 + 候选分析维度）。
     *
     * <p>返回的 {@link MetricAnalysisContext} 可直接传入
     * {@link com.javacodeagent.core.data.agent.DataAnalysisAgent} 的归因链路。
     *
     * @param metricName 指标名称（必须已注册）
     * @param lookbackDays 查询历史天数（默认 30）
     * @return 指标分析上下文
     */
    public Mono<MetricAnalysisContext> getMetricInfo(String metricName, int lookbackDays) {
        MetricDefinition def = metricRegistry.get(metricName);
        if (def == null) {
            return Mono.error(new IllegalArgumentException(
                "Metric not found: '" + metricName + "'. Available: " + metricRegistry.keySet()));
        }

        int days = lookbackDays > 0 ? lookbackDays : 30;

        // 并行查询：当前聚合值 + 历史趋势
        Mono<DataQueryResult> currentMono  = queryCurrentValue(def);
        Mono<DataQueryResult> historyMono  = queryHistory(def, days);

        return Mono.zip(currentMono, historyMono)
            .map(tuple -> MetricAnalysisContext.builder()
                .metricName(metricName)
                .definition(def)
                .currentResult(tuple.getT1())
                .historicalResult(tuple.getT2())
                .lookbackDays(days)
                .suggestedDimensions(def.dimensions())
                .build())
            .doOnSuccess(ctx -> log.debug("MetricInfo retrieved: metric={}, currentRows={}, historyRows={}",
                metricName, ctx.getCurrentResult().totalRows(), ctx.getHistoricalResult().totalRows()));
    }

    // ── 私有查询方法 ──────────────────────────────────────────────────────────

    /** 查询指标当前聚合值（如本日/本月总量）。 */
    private Mono<DataQueryResult> queryCurrentValue(MetricDefinition def) {
        String sql = def.currentValueSql() != null && !def.currentValueSql().isBlank()
            ? def.currentValueSql()
            : buildDefaultCurrentSql(def);

        SqlValidationResult valid = sqlValidator.validate(sql);
        if (!valid.isAllowed()) {
            log.warn("Metric current-value SQL rejected: {}", valid.reason());
            return Mono.just(DataQueryResult.empty(sql));
        }
        return sqlExecutor.execute(sql, 1, Duration.ofSeconds(15))
            .onErrorResume(e -> {
                log.warn("Failed to query current value for metric '{}': {}", def.name(), e.getMessage());
                return Mono.just(DataQueryResult.empty(sql));
            });
    }

    /** 查询指标历史趋势（最近 N 天，按时间列 GROUP BY）。 */
    private Mono<DataQueryResult> queryHistory(MetricDefinition def, int days) {
        String sql = buildHistorySql(def, days);
        SqlValidationResult valid = sqlValidator.validate(sql);
        if (!valid.isAllowed()) {
            log.warn("Metric history SQL rejected: {}", valid.reason());
            return Mono.just(DataQueryResult.empty(sql));
        }
        return sqlExecutor.execute(sql, DataAgentConstants.DEFAULT_MAX_ROWS, Duration.ofSeconds(20))
            .onErrorResume(e -> {
                log.warn("Failed to query history for metric '{}': {}", def.name(), e.getMessage());
                return Mono.just(DataQueryResult.empty(sql));
            });
    }

    private String buildDefaultCurrentSql(MetricDefinition def) {
        String agg = def.aggregation() != null ? def.aggregation() : "SUM";
        return String.format("SELECT %s(%s) AS current_value FROM %s",
            agg, q(def.valueColumn()), q(def.table()));
    }

    private String buildHistorySql(MetricDefinition def, int days) {
        if (def.historySql() != null && !def.historySql().isBlank()) {
            return def.historySql().replace("{lookback_days}", String.valueOf(days));
        }
        if (def.timeColumn() == null || def.timeColumn().isBlank()) {
            return buildDefaultCurrentSql(def);
        }
        String agg = def.aggregation() != null ? def.aggregation() : "SUM";
        String tCol = q(def.timeColumn());
        String vCol = q(def.valueColumn());
        String tbl  = q(def.table());
        return switch (connector.getDialect()) {
            case "mysql" -> String.format(
                "SELECT DATE(%s) AS dt, %s(%s) AS value FROM %s " +
                "WHERE %s >= DATE_SUB(CURDATE(), INTERVAL %d DAY) " +
                "GROUP BY DATE(%s) ORDER BY dt",
                tCol, agg, vCol, tbl, tCol, days, tCol);
            case "postgresql" -> String.format(
                "SELECT DATE_TRUNC('day', %s) AS dt, %s(%s) AS value FROM %s " +
                "WHERE %s >= NOW() - INTERVAL '%d days' " +
                "GROUP BY DATE_TRUNC('day', %s) ORDER BY dt",
                tCol, agg, vCol, tbl, tCol, days, tCol);
            default -> // h2 / sqlite
                String.format(
                "SELECT CAST(%s AS DATE) AS dt, %s(%s) AS value FROM %s " +
                "WHERE %s >= DATEADD(DAY, -%d, CURRENT_DATE) " +
                "GROUP BY CAST(%s AS DATE) ORDER BY dt",
                tCol, agg, vCol, tbl, tCol, days, tCol);
        };
    }

    // ── 数据模型 ──────────────────────────────────────────────────────────────

    /**
     * 指标定义。
     *
     * @param name            指标唯一名称（如 "daily_order_count"）
     * @param displayName     展示名（如 "日订单量"）
     * @param table           数据表名
     * @param valueColumn     指标值列名
     * @param timeColumn      时间列名（可为空，无则不生成历史 SQL）
     * @param aggregation     聚合函数（SUM / COUNT / AVG 等，默认 SUM）
     * @param dimensions      候选分析维度列名列表（如 ["region", "product_type"]）
     * @param currentValueSql 自定义当前值查询 SQL（可为空，使用默认）
     * @param historySql      自定义历史趋势 SQL（可包含 {lookback_days} 占位符）
     * @param description     指标描述
     */
    public record MetricDefinition(
        String name,
        String displayName,
        String table,
        String valueColumn,
        String timeColumn,
        String aggregation,
        List<String> dimensions,
        String currentValueSql,
        String historySql,
        String description
    ) {
        public static MetricDefinition simple(String name, String displayName,
                                              String table, String valueColumn) {
            return new MetricDefinition(name, displayName, table, valueColumn,
                null, "SUM", new ArrayList<>(), null, null, "");
        }

        public static MetricDefinition withTime(String name, String displayName,
                                                String table, String valueColumn,
                                                String timeColumn, List<String> dimensions) {
            return new MetricDefinition(name, displayName, table, valueColumn,
                timeColumn, "SUM", dimensions, null, null, "");
        }
    }

    /**
     * 指标分析上下文（传入归因链路 Agent）。
     */
    @lombok.Builder
    @lombok.Data
    public static class MetricAnalysisContext {
        private String metricName;
        private MetricDefinition definition;
        /** 指标当前聚合值查询结果（通常 1 行）。 */
        private DataQueryResult currentResult;
        /** 历史趋势数据（时间序列，多行）。 */
        private DataQueryResult historicalResult;
        private int lookbackDays;
        /** 候选分析维度（来自 MetricDefinition.dimensions）。 */
        private List<String> suggestedDimensions;

        /** 返回适合传入 LLM 的文本摘要（当前值 + 历史趋势概要）。 */
        public String toPromptText() {
            StringBuilder sb = new StringBuilder();
            sb.append("Metric: ").append(metricName).append("\n");
            if (definition.description() != null && !definition.description().isBlank()) {
                sb.append("Description: ").append(definition.description()).append("\n");
            }
            sb.append("Lookback: ").append(lookbackDays).append(" days\n");

            // 当前值
            sb.append("\nCurrent value:\n");
            if (currentResult != null && !currentResult.rows().isEmpty()) {
                currentResult.columns().forEach(c -> sb.append(c).append("\t"));
                sb.append("\n");
                currentResult.rows().get(0).forEach(v -> sb.append(v).append("\t"));
                sb.append("\n");
            } else {
                sb.append("(no data)\n");
            }

            // 历史趋势（最多 30 行）
            sb.append("\nHistorical trend (up to 30 rows):\n");
            if (historicalResult != null && !historicalResult.rows().isEmpty()) {
                historicalResult.columns().forEach(c -> sb.append(c).append("\t"));
                sb.append("\n");
                historicalResult.rows().stream().limit(30).forEach(row -> {
                    row.forEach(v -> sb.append(v).append("\t"));
                    sb.append("\n");
                });
            } else {
                sb.append("(no data)\n");
            }

            if (suggestedDimensions != null && !suggestedDimensions.isEmpty()) {
                sb.append("\nSuggested analysis dimensions: ")
                  .append(String.join(", ", suggestedDimensions)).append("\n");
            }
            return sb.toString();
        }
    }
}
