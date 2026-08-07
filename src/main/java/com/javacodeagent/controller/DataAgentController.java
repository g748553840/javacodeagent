package com.javacodeagent.controller;

import com.javacodeagent.core.data.DataAgentPipeline;
import com.javacodeagent.core.data.DataSourceManager;
import com.javacodeagent.core.data.MetricAnalysisPipeline;
import com.javacodeagent.core.data.MetricInfoRetriever;
import com.javacodeagent.core.data.SchemaRetriever;
import com.javacodeagent.core.data.agent.DataAnalysisAgent;
import com.javacodeagent.core.data.model.DataAnalysisReport;
import com.javacodeagent.core.data.model.DashboardSpec;
import com.javacodeagent.core.data.model.DataQueryRequest;
import com.javacodeagent.core.data.model.DataQueryResult;
import com.javacodeagent.core.data.model.MetricAnalysisReport;
import com.javacodeagent.core.data.model.MetricAnalysisRequest;
import com.javacodeagent.core.data.model.MultiAnalysisReport;
import com.javacodeagent.core.data.model.Nl2SqlResult;
import com.javacodeagent.core.agent.AgentContext;
import com.javacodeagent.core.agent.AgentResult;
import com.javacodeagent.core.agent.AgentTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/data-agent")
@RequiredArgsConstructor
public class DataAgentController {

    private final DataAgentPipeline pipeline;
    private final DataAnalysisAgent dataAnalysisAgent;
    private final DataSourceManager dataSourceManager;
    private final SchemaRetriever schemaRetriever;
    private final MetricInfoRetriever metricInfoRetriever;
    private final MetricAnalysisPipeline metricAnalysisPipeline;
    private final ObjectMapper objectMapper;

    /**
     * 完整 NL→SQL→执行→洞察分析。
     */
    @PostMapping("/query")
    public Mono<ResponseEntity<DataAnalysisReport>> query(@RequestBody DataQueryRequest request) {
        return pipeline.analyze(request)
            .map(ResponseEntity::ok);
    }

    /**
     * SSE 流式分析，逐步返回 schema_retrieved → sql_generated → sql_executed → insight_ready → done 事件。
     */
    @PostMapping(value = "/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> queryStream(@RequestBody DataQueryRequest request) {
        return pipeline.analyzeStream(request);
    }

    /**
     * 仅生成 SQL（不执行），供用户审核后调用 /execute。
     */
    @PostMapping("/nl2sql")
    public Mono<ResponseEntity<Nl2SqlResult>> nl2sql(@RequestBody DataQueryRequest request) {
        return pipeline.generateSqlOnly(request)
            .map(ResponseEntity::ok)
            .onErrorResume(e -> Mono.just(ResponseEntity.badRequest()
                .body(Nl2SqlResult.builder().thought("Error: " + e.getMessage()).build())));
    }

    /**
     * 执行用户确认后的 SQL（经安全校验）。
     */
    @PostMapping("/execute")
    public Mono<ResponseEntity<DataQueryResult>> execute(@RequestBody Map<String, String> body) {
        String sql = body.get("sql");
        if (sql == null || sql.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        return pipeline.executeSql(sql)
            .map(ResponseEntity::ok)
            .onErrorResume(e -> {
                log.warn("Execute failed: {}", e.getMessage());
                return Mono.just(ResponseEntity.badRequest().build());
            });
    }

    /**
     * 生成多图 Dashboard（LLM 规划 2-4 张互补图表）。
     */
    @PostMapping("/dashboard")
    public Mono<ResponseEntity<DashboardSpec>> dashboard(@RequestBody DataQueryRequest request) {
        return pipeline.generateDashboard(request)
            .map(ResponseEntity::ok);
    }

    /**
     * 获取数据库 Schema 概览。
     * 可选 ?dataSourceId=xxx 指定数据源；未指定时使用默认数据源。
     */
    @GetMapping("/schema")
    public Mono<ResponseEntity<Map<String, Object>>> schema(
            @RequestParam(required = false) String dataSourceId) {
        return pipeline.getSchema(dataSourceId)
            .map(ResponseEntity::ok);
    }

    // ── 多数据源管理 ───────────────────────────────────────────────────────────

    /** 列出所有已注册的数据源。 */
    @GetMapping("/datasources")
    public ResponseEntity<List<DataSourceManager.DataSourceInfo>> listDataSources() {
        return ResponseEntity.ok(dataSourceManager.listDataSources());
    }

    /**
     * 动态注册外部数据源。
     *
     * <pre>
     * {
     *   "id":          "my-mysql",
     *   "url":         "jdbc:mysql://host:3306/mydb",
     *   "username":    "root",
     *   "password":    "secret",
     *   "driverClass": "com.mysql.cj.jdbc.Driver",   // 可选
     *   "dialect":     "mysql",
     *   "dbName":      "mydb",
     *   "description": "生产 MySQL"
     * }
     * </pre>
     */
    @PostMapping("/datasources")
    public ResponseEntity<Map<String, String>> registerDataSource(@RequestBody Map<String, String> body) {
        String id = body.get("id");
        if (id == null || id.isBlank()) {
            id = "ds-" + UUID.randomUUID().toString().substring(0, 8);
        }
        String url = body.get("url");
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "url is required"));
        }
        try {
            dataSourceManager.registerJdbc(
                id,
                url,
                body.getOrDefault("username", ""),
                body.getOrDefault("password", ""),
                body.getOrDefault("driverClass", ""),
                body.getOrDefault("dialect", "mysql"),
                body.getOrDefault("dbName", ""),
                body.getOrDefault("description", "")
            );
            return ResponseEntity.ok(Map.of("id", id, "status", "registered"));
        } catch (Exception e) {
            log.warn("Failed to register datasource '{}': {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 注销数据源（不能注销 default），同时清理 Schema 向量索引缓存。 */
    @DeleteMapping("/datasources/{id}")
    public ResponseEntity<Map<String, String>> unregisterDataSource(@PathVariable String id) {
        try {
            dataSourceManager.unregister(id);
            // 数据源已移除，清理对应的 Schema 向量索引，防止陈旧向量占用内存
            schemaRetriever.invalidateIndex(id);
            return ResponseEntity.ok(Map.of("id", id, "status", "unregistered"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 清除指定数据源的 Schema 向量索引缓存（表结构变更后调用）。 */
    @PostMapping("/datasources/{id}/invalidate-schema-index")
    public ResponseEntity<Map<String, String>> invalidateSchemaIndex(@PathVariable String id) {
        if (!dataSourceManager.contains(id)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Datasource not found: " + id));
        }
        schemaRetriever.invalidateIndex(id);
        return ResponseEntity.ok(Map.of("id", id, "status", "index_invalidated"));
    }

    // ── 指标体系 ───────────────────────────────────────────────────────────────

    /**
     * 列出所有已注册的指标定义。
     */
    @GetMapping("/metrics")
    public ResponseEntity<List<MetricInfoRetriever.MetricDefinition>> listMetrics() {
        return ResponseEntity.ok(metricInfoRetriever.listMetrics());
    }

    /**
     * 动态注册指标定义。
     *
     * <pre>
     * {
     *   "name":        "daily_order_count",
     *   "displayName": "日订单量",
     *   "table":       "orders",
     *   "valueColumn": "order_id",
     *   "timeColumn":  "created_at",
     *   "aggregation": "COUNT",
     *   "dimensions":  ["region", "product_type"],
     *   "description": "每日新增订单数量"
     * }
     * </pre>
     */
    @PostMapping("/metrics")
    public ResponseEntity<Map<String, String>> registerMetric(@RequestBody Map<String, Object> body) {
        try {
            String name        = (String) body.get("name");
            String displayName = (String) body.getOrDefault("displayName", name);
            String table       = (String) body.get("table");
            String valueColumn = (String) body.get("valueColumn");
            String timeColumn  = (String) body.getOrDefault("timeColumn", null);
            String aggregation = (String) body.getOrDefault("aggregation", "SUM");
            String description = (String) body.getOrDefault("description", "");
            String currentSql  = (String) body.getOrDefault("currentValueSql", null);
            String historySql  = (String) body.getOrDefault("historySql", null);
            @SuppressWarnings("unchecked")
            List<String> dimensions = body.get("dimensions") instanceof List<?> l
                ? (List<String>) l : List.of();

            if (name == null || table == null || valueColumn == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "name, table, valueColumn are required"));
            }

            metricInfoRetriever.register(new MetricInfoRetriever.MetricDefinition(
                name, displayName, table, valueColumn, timeColumn,
                aggregation, dimensions, currentSql, historySql, description));

            return ResponseEntity.ok(Map.of("name", name, "status", "registered"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 注销指标定义。
     */
    @DeleteMapping("/metrics/{name}")
    public ResponseEntity<Map<String, String>> unregisterMetric(@PathVariable String name) {
        metricInfoRetriever.unregister(name);
        return ResponseEntity.ok(Map.of("name", name, "status", "unregistered"));
    }

    /**
     * 指标归因分析（完整链路：MetricInfo → Anomaly + Volatility → Report）。
     *
     * <pre>
     * { "metricName": "daily_order_count", "lookbackDays": 30 }
     * </pre>
     */
    @PostMapping("/metric-analysis")
    public Mono<ResponseEntity<MetricAnalysisReport>> metricAnalysis(
            @RequestBody MetricAnalysisRequest request) {
        return metricAnalysisPipeline.analyze(request)
            .map(ResponseEntity::ok)
            .onErrorResume(e -> {
                log.error("Metric analysis error: {}", e.getMessage(), e);
                return Mono.just(ResponseEntity.ok(
                    MetricAnalysisReport.error(
                        request != null ? request.getMetricName() : "unknown",
                        e.getMessage())));
            });
    }

    /**
     * 多 Agent 协作分析（异常检测 + 波动分析 + 综合报告）。
     *
     * <p>执行顺序：
     * <ol>
     *   <li>NL2SQL 管道获取查询数据</li>
     *   <li>AnomalyDetectorAgent + VolatilityAnalysisAgent 并行分析</li>
     *   <li>ReportGenerationAgent 生成 Markdown 综合报告</li>
     * </ol>
     *
     * <p>全程在 boundedElastic 线程上执行（含 LLM .block() 调用），不阻塞 Netty IO 线程。
     */
    @PostMapping("/multi-analysis")
    public Mono<ResponseEntity<MultiAnalysisReport>> multiAnalysis(@RequestBody DataQueryRequest request) {
        return Mono.fromCallable(() -> {
            DataAnalysisReport baseReport = pipeline.analyze(request).block();
            if (baseReport == null || !baseReport.isSuccess()) {
                String msg = baseReport != null ? baseReport.getErrorMessage() : "Pipeline returned null";
                return ResponseEntity.ok(MultiAnalysisReport.error(request.getQuestion(), msg));
            }
            AgentTask task = AgentTask.builder()
                .type("data-analysis")
                .description(request.getQuestion())
                .parameters(Map.of("report", baseReport, "question", request.getQuestion()))
                .build();
            AgentContext ctx = AgentContext.builder()
                .agentId(UUID.randomUUID().toString())
                .build();
            AgentResult result = dataAnalysisAgent.process(task, ctx);
            if (!result.isSuccess()) {
                return ResponseEntity.ok(MultiAnalysisReport.error(request.getQuestion(), result.getError()));
            }
            if (result.getOutput() == null || result.getOutput().isBlank()) {
                return ResponseEntity.ok(MultiAnalysisReport.error(request.getQuestion(), "Agent returned no output"));
            }
            MultiAnalysisReport report = objectMapper.readValue(result.getOutput(), MultiAnalysisReport.class);
            return ResponseEntity.ok(report);
        })
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorResume(e -> {
            log.error("Multi-analysis failed for question: {}", request.getQuestion(), e);
            return Mono.just(ResponseEntity.ok(
                MultiAnalysisReport.error(request.getQuestion(), e.getMessage())));
        });
    }
}
