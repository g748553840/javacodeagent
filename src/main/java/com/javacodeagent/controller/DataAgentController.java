package com.javacodeagent.controller;

import com.javacodeagent.core.data.DataAgentPipeline;
import com.javacodeagent.core.data.model.DataAnalysisReport;
import com.javacodeagent.core.data.model.DashboardSpec;
import com.javacodeagent.core.data.model.DataQueryRequest;
import com.javacodeagent.core.data.model.DataQueryResult;
import com.javacodeagent.core.data.model.Nl2SqlResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/data-agent")
@RequiredArgsConstructor
public class DataAgentController {

    private final DataAgentPipeline pipeline;

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
     */
    @GetMapping("/schema")
    public Mono<ResponseEntity<Map<String, Object>>> schema(
            @RequestParam(defaultValue = "default") String dataSourceId) {
        return pipeline.getSchema()
            .map(ResponseEntity::ok);
    }
}
