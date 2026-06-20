package com.javacodeagent.core.data;

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

import java.time.Duration;
import java.util.List;
import java.util.Map;

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

    public Flux<String> analyzeStream(DataQueryRequest request) {
        return Flux.create(sink -> {
            sink.next("{\"type\":\"started\",\"question\":\"" + request.getQuestion() + "\"}");

            schemaRetriever.retrieve(request.getQuestion())
                .doOnNext(schema -> sink.next("{\"type\":\"schema_retrieved\",\"length\":" + schema.length() + "}"))
                .flatMap(schema -> nl2SqlService.generateSql(request.getQuestion(), schema))
                .doOnNext(r -> sink.next("{\"type\":\"sql_generated\",\"sql\":" + jsonStr(r.getSql()) +
                    ",\"displayType\":" + jsonStr(r.getDisplayType()) + "}"))
                .flatMap(nl2SqlResult -> validateAndExecute(nl2SqlResult, request.getMaxRows()))
                .doOnNext(cs -> sink.next("{\"type\":\"sql_executed\",\"rowCount\":" + (cs.getData() != null ? cs.getData().size() : 0) + "}"))
                .flatMap(chartSpec -> insightGenerator.generate(request.getQuestion(), chartSpec))
                .subscribe(
                    insight -> {
                        sink.next("{\"type\":\"insight_ready\",\"markdown\":" + jsonStr(insight.markdown()) + "}");
                        sink.next("{\"type\":\"done\"}");
                        sink.complete();
                    },
                    err -> {
                        sink.next("{\"type\":\"error\",\"message\":" + jsonStr(err.getMessage()) + "}");
                        sink.complete();
                    }
                );
        });
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
        return sqlExecutor.execute(sql, 200, Duration.ofSeconds(30));
    }

    // ── Dashboard（多图） ─────────────────────────────────────────────────────

    public Mono<DashboardSpec> generateDashboard(DataQueryRequest request) {
        return dashboardGenerator.generate(request.getQuestion());
    }

    // ── Schema 信息 ───────────────────────────────────────────────────────────

    public Mono<Map<String, Object>> getSchema() {
        List<String> tables = connector.listTables();
        return Mono.just(Map.of(
            "database", connector.getDatabaseName(),
            "dialect", connector.getDialect(),
            "tables", tables,
            "tableCount", tables.size()
        ));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Mono<ChartSpec> validateAndExecute(Nl2SqlResult nl2SqlResult, int maxRows) {
        SqlValidationResult valid = sqlValidator.validate(nl2SqlResult.getSql());
        if (!valid.isAllowed()) {
            return Mono.error(new IllegalArgumentException("SQL rejected: " + valid.reason()));
        }
        return sqlExecutor.execute(nl2SqlResult.getSql(), maxRows, Duration.ofSeconds(30))
            .map(qr -> ChartSpec.from(nl2SqlResult, qr));
    }

    private String jsonStr(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
