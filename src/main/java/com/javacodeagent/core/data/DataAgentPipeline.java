package com.javacodeagent.core.data;

import com.javacodeagent.core.data.DataAgentConstants;
import com.javacodeagent.core.data.model.ChartSpec;
import com.javacodeagent.core.data.model.DataAnalysisReport;
import com.javacodeagent.core.data.model.DataQueryRequest;
import com.javacodeagent.core.data.model.DataQueryResult;
import com.javacodeagent.core.data.model.DashboardSpec;
import com.javacodeagent.core.data.model.Nl2SqlResult;
import com.javacodeagent.core.data.model.SqlValidationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import reactor.core.scheduler.Schedulers;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataAgentPipeline {

    private final DataSourceConnector connector;
    private final SchemaRetriever schemaRetriever;
    private final Nl2SqlService nl2SqlService;
    private final SqlValidator sqlValidator;
    private final SqlExecutor sqlExecutor;
    private final InsightGenerator insightGenerator;
    private final DashboardGenerator dashboardGenerator;

    // ── 完整分析（Schema → SQL → 执行 → 洞察） ──────────────────────────────

    public Mono<DataAnalysisReport> analyze(DataQueryRequest request) {
        return schemaRetriever.retrieve(request.getQuestion())
            .flatMap(schema -> nl2SqlService.generateSql(request.getQuestion(), schema))
            .flatMap(nl2SqlResult -> validateAndExecute(nl2SqlResult, request.getMaxRows()))
            .flatMap(chartSpec -> insightGenerator.generate(request.getQuestion(), chartSpec))
            .map(insight -> DataAnalysisReport.builder()
                .question(request.getQuestion())
                .chartSpec(insight.chartSpec())
                .insightMarkdown(insight.markdown())
                .success(true)
                .build())
            .doOnError(e -> log.error("Data analysis failed: {}", e.getMessage()))
            .onErrorResume(e -> Mono.just(DataAnalysisReport.error(e.getMessage())));
    }

    // ── 流式分析（SSE 进度事件） ─────────────────────────────────────────────

    /**
     * 逐步推送分析进度（schema_retrieved → sql_generated → sql_executed → insight_ready → done）。
     * <p>
     * 使用 Flux.concat 链式组合而非 Flux.create + subscribe（Reactor 嵌套订阅反模式），
     * 保证背压传播正确，防止在高并发下内存积压。
     */
    public Flux<String> analyzeStream(DataQueryRequest request) {
        return Flux.concat(
            Flux.just(sse("{\"type\":\"started\",\"question\":" + jsonStr(request.getQuestion()) + "}")),
            schemaRetriever.retrieve(request.getQuestion())
                .flatMapMany(schema -> Flux.concat(
                    Flux.just(sse("{\"type\":\"schema_retrieved\",\"length\":" + schema.length() + "}")),
                    nl2SqlService.generateSql(request.getQuestion(), schema)
                        .flatMapMany(nl2SqlResult -> Flux.concat(
                            Flux.just(sse("{\"type\":\"sql_generated\",\"sql\":" + jsonStr(nl2SqlResult.getSql())
                                + ",\"displayType\":" + jsonStr(nl2SqlResult.getDisplayType()) + "}")),
                            validateAndExecute(nl2SqlResult, request.getMaxRows())
                                .flatMapMany(chartSpec -> {
                                    int rowCount = chartSpec.getData() != null ? chartSpec.getData().size() : 0;
                                    return Flux.concat(
                                        Flux.just(sse("{\"type\":\"sql_executed\",\"rowCount\":" + rowCount + "}")),
                                        insightGenerator.generate(request.getQuestion(), chartSpec)
                                            .flatMapMany(insight -> Flux.just(
                                                sse("{\"type\":\"insight_ready\",\"markdown\":" + jsonStr(insight.markdown()) + "}"),
                                                sse("{\"type\":\"done\"}")
                                            ))
                                    );
                                })
                        ))
                ))
        ).onErrorResume(err -> Flux.just(
            sse("{\"type\":\"error\",\"message\":" + jsonStr(err.getMessage()) + "}"),
            sse("{\"type\":\"done\"}")
        ));
    }

    // ── 仅生成 SQL（不执行） ──────────────────────────────────────────────────

    public Mono<Nl2SqlResult> generateSqlOnly(DataQueryRequest request) {
        return schemaRetriever.retrieve(request.getQuestion())
            .flatMap(schema -> nl2SqlService.generateSql(request.getQuestion(), schema));
    }

    // ── 执行已有 SQL ──────────────────────────────────────────────────────────

    public Mono<DataQueryResult> executeSql(String sql) {
        SqlValidationResult valid = sqlValidator.validate(sql);
        if (!valid.isAllowed()) {
            return Mono.error(new IllegalArgumentException("SQL rejected: " + valid.reason()));
        }
        return sqlExecutor.execute(sql, DataAgentConstants.DEFAULT_MAX_ROWS, DataAgentConstants.DEFAULT_QUERY_TIMEOUT);
    }

    // ── Dashboard（多图） ─────────────────────────────────────────────────────

    public Mono<DashboardSpec> generateDashboard(DataQueryRequest request) {
        return dashboardGenerator.generate(request.getQuestion());
    }

    // ── Schema 信息 ───────────────────────────────────────────────────────────

    /**
     * 返回当前数据源的 Schema 概要（表名列表、方言、数据库名）。
     * listTables() 是阻塞 JDBC 调用，必须切换到 boundedElastic 线程，
     * 否则会阻塞 Netty IO 事件循环。
     */
    public Mono<Map<String, Object>> getSchema() {
        return Mono.fromCallable(() -> {
            List<String> tables = connector.listTables();
            return Map.<String, Object>of(
                "database", connector.getDatabaseName(),
                "dialect", connector.getDialect(),
                "tables", tables,
                "tableCount", tables.size()
            );
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** 将 JSON 字符串包装为 SSE data 行（格式：{@code data: <json>\n\n}）。 */
    private static String sse(String json) {
        return "data: " + json + "\n\n";
    }

    private Mono<ChartSpec> validateAndExecute(Nl2SqlResult nl2SqlResult, int maxRows) {
        SqlValidationResult valid = sqlValidator.validate(nl2SqlResult.getSql());
        if (!valid.isAllowed()) {
            return Mono.error(new IllegalArgumentException("SQL rejected: " + valid.reason()));
        }
        return sqlExecutor.execute(nl2SqlResult.getSql(), maxRows, DataAgentConstants.DEFAULT_QUERY_TIMEOUT)
            .map(qr -> ChartSpec.from(nl2SqlResult, qr));
    }

    /**
     * 将字符串值序列化为 JSON 字符串字面量（含双引号包裹）。
     * 按 RFC 8259 转义所有必须转义的字符：
     * - 有具名转义的控制字符（\n \r \t \b \f）
     * - 其余 0x00–0x1F 控制字符使用 \uXXXX 格式
     * - 反斜杠和双引号
     */
    private String jsonStr(String value) {
        if (value == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        // 其余控制字符（NUL、BEL、VT 等）用 \uXXXX 转义
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
