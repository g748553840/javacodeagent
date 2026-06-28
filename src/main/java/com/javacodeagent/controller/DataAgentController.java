package com.javacodeagent.controller;

import com.javacodeagent.core.data.DataAgentPipeline;
import com.javacodeagent.core.data.agent.DataAnalysisAgent;
import com.javacodeagent.core.data.model.DataAnalysisReport;
import com.javacodeagent.core.data.model.DashboardSpec;
import com.javacodeagent.core.data.model.DataQueryRequest;
import com.javacodeagent.core.data.model.DataQueryResult;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/data-agent")
@RequiredArgsConstructor
public class DataAgentController {

    private final DataAgentPipeline pipeline;
    private final DataAnalysisAgent dataAnalysisAgent;
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
     * 当前为单数据源模式；多数据源路由可在 DataAgentPipeline.getSchema() 中扩展。
     */
    @GetMapping("/schema")
    public Mono<ResponseEntity<Map<String, Object>>> schema() {
        return pipeline.getSchema()
            .map(ResponseEntity::ok);
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
