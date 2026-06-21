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
        String startedEvent = "{\"type\":\"started\",\"question\":" + jsonStr(request.getQuestion()) + "}";

        Flux<String> pipeline = schemaRetriever.retrieve(request.getQuestion())
            .flatMapMany(schema -> {
                String schemaEvent = "{\"type\":\"schema_retrieved\",\"length\":" + schema.length() + "}";
                return Flux.concat(
                    Flux.just(schemaEvent),
                    nl2SqlService.generateSql(request.getQuestion(), schema)
                        .flatMapMany(nl2SqlResult -> {
                            String sqlEvent = "{\"type\":\"sql_generated\",\"sql\":" + jsonStr(nl2SqlResult.getSql())
                                + ",\"displayType\":" + jsonStr(nl2SqlResult.getDisplayType()) + "}";
                            return Flux.concat(
                                Flux.just(sqlEvent),
                                validateAndExecute(nl2SqlResult, request.getMaxRows())
                                    .flatMapMany(chartSpec -> {
                                        int rowCount = chartSpec.getData() != null ? chartSpec.getData().size() : 0;
                                        String execEvent = "{\"type\":\"sql_executed\",\"rowCount\":" + rowCount + "}";
                                        return Flux.concat(
                                            Flux.just(execEvent),
                                            insightGenerator.generate(request.getQuestion(), chartSpec)
                                                .flatMapMany(insight -> Flux.just(
                                                    "{\"type\":\"insight_ready\",\"markdown\":" + jsonStr(insight.markdown()) + "}",
                                                    "{\"type\":\"done\"}"
                                                ))
                                        );
                                    })
                            );
                        })
                );
            });

        return Flux.concat(Flux.just(startedEvent), pipeline)
            .onErrorResume(err -> Flux.just(
                "{\"type\":\"error\",\"message\":" + jsonStr(err.getMessage()) + "}",
                "{\"type\":\"done\"}"
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

    private Mono<ChartSpec> validateAndExecute(Nl2SqlResult nl2SqlResult, int maxRows) {
        SqlValidationResult valid = sqlValidator.validate(nl2SqlResult.getSql());
        if (!valid.isAllowed()) {
            return Mono.error(new IllegalArgumentException("SQL rejected: " + valid.reason()));
        }
        return sqlExecutor.execute(nl2SqlResult.getSql(), maxRows, DataAgentConstants.DEFAULT_QUERY_TIMEOUT)
            .map(qr -> ChartSpec.from(nl2SqlResult, qr));
    }

    private String jsonStr(String value) {
        if (value == null) return "null";
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\b", "\\b")
            .replace("\f", "\\f") + "\"";
    }
}
