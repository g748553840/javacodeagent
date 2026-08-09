package com.javacodeagent.core.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javacodeagent.core.agent.AgentContext;
import com.javacodeagent.core.agent.AgentResult;
import com.javacodeagent.core.agent.AgentTask;
import com.javacodeagent.core.data.agent.AnomalyDetectorAgent;
import com.javacodeagent.core.data.agent.ReportGenerationAgent;
import com.javacodeagent.core.data.agent.VolatilityAnalysisAgent;
import com.javacodeagent.core.data.model.MetricAnalysisReport;
import com.javacodeagent.core.data.model.MetricAnalysisRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * 指标归因分析流水线（仿 DB-GPT 归因分析链路）。
 *
 * <pre>
 * MetricInfoRetriever.getMetricInfo()
 *     │
 *     ▼ MetricAnalysisContext（当前值 + 历史趋势 + 候选维度）
 *     │
 *     ├── AnomalyDetectorAgent    ─┐  并行（Java 21 虚拟线程）
 *     ├── VolatilityAnalysisAgent ─┘
 *     │
 *     └── ReportGenerationAgent（聚合 → MetricAnalysisReport）
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricAnalysisPipeline {

    private final MetricInfoRetriever metricInfoRetriever;
    private final AnomalyDetectorAgent anomalyDetector;
    private final VolatilityAnalysisAgent volatilityAnalyzer;
    private final ReportGenerationAgent reportGenerator;
    private final ObjectMapper objectMapper;

    /**
     * 执行完整的指标归因分析。
     *
     * @param request 分析请求（metricName + lookbackDays）
     * @return 指标归因分析报告（Mono，在 boundedElastic 线程执行）
     */
    public Mono<MetricAnalysisReport> analyze(MetricAnalysisRequest request) {
        if (request == null || request.getMetricName() == null || request.getMetricName().isBlank()) {
            return Mono.error(new IllegalArgumentException("metricName must not be blank"));
        }

        return metricInfoRetriever.getMetricInfo(request.getMetricName(), request.getLookbackDays())
            .flatMap(ctx -> Mono.fromCallable(() -> runAgentPipeline(ctx))
                .subscribeOn(Schedulers.boundedElastic()))
            .onErrorResume(e -> {
                log.error("Metric analysis failed for '{}': {}", request.getMetricName(), e.getMessage());
                return Mono.just(MetricAnalysisReport.error(request.getMetricName(), e.getMessage()));
            });
    }

    // ── 私有方法 ──────────────────────────────────────────────────────────────

    private MetricAnalysisReport runAgentPipeline(MetricInfoRetriever.MetricAnalysisContext ctx) {
        String dataSample = ctx.toPromptText();
        String question = "Analyze metric: " + ctx.getMetricName();

        Map<String, Object> agentParams = Map.of(
            "question", question,
            "dataSample", dataSample
        );

        AgentTask anomalyTask = AgentTask.builder()
            .type(anomalyDetector.getType())
            .description("Detect anomalies in metric: " + ctx.getMetricName())
            .parameters(agentParams)
            .build();

        AgentTask volatilityTask = AgentTask.builder()
            .type(volatilityAnalyzer.getType())
            .description("Analyze volatility in metric: " + ctx.getMetricName())
            .parameters(agentParams)
            .build();

        AgentContext agentCtx = AgentContext.builder()
            .agentId(UUID.randomUUID().toString())
            .build();

        // 并行执行异常检测 + 波动分析
        AgentResult anomalyResult;
        AgentResult volatilityResult;
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<AgentResult> anomalyFuture  = vt.submit(() -> anomalyDetector.process(anomalyTask, agentCtx));
            Future<AgentResult> volatilityFuture = vt.submit(() -> volatilityAnalyzer.process(volatilityTask, agentCtx));
            anomalyResult    = anomalyFuture.get();
            volatilityResult = volatilityFuture.get();
        } catch (Exception e) {
            log.error("Parallel agent execution failed for metric '{}'", ctx.getMetricName(), e);
            return MetricAnalysisReport.error(ctx.getMetricName(), e.getMessage());
        }

        String anomaliesJson  = anomalyResult.isSuccess()    ? anomalyResult.getOutput()    : "[]";
        String volatilityJson = volatilityResult.isSuccess() ? volatilityResult.getOutput() : "{}";

        // 生成综合报告
        Map<String, Object> reportParams = new HashMap<>();
        reportParams.put("question", "对指标 " + ctx.getMetricName() + " 进行归因分析（过去 " + ctx.getLookbackDays() + " 天）");
        reportParams.put("dataSummary", dataSample);
        reportParams.put("anomalies",   anomaliesJson != null ? anomaliesJson : "[]");
        reportParams.put("volatility",  volatilityJson != null ? volatilityJson : "{}");

        AgentTask reportTask = AgentTask.builder()
            .type(reportGenerator.getType())
            .description("Generate metric analysis report for: " + ctx.getMetricName())
            .parameters(reportParams)
            .build();
        AgentResult reportResult = reportGenerator.process(reportTask, agentCtx);

        // 组装最终报告
        List<String> anomalies = parseAnomalies(anomaliesJson);
        Map<String, Object> volatilityMetrics = parseVolatility(volatilityJson);
        List<Map<String, Object>> historicalData = toMapList(ctx.getHistoricalResult());
        Object currentValue = extractCurrentValue(ctx.getCurrentResult());

        return MetricAnalysisReport.builder()
            .metricName(ctx.getMetricName())
            .displayName(ctx.getDefinition().displayName())
            .lookbackDays(ctx.getLookbackDays())
            .dimensions(ctx.getSuggestedDimensions())
            .currentValue(currentValue)
            .historicalData(historicalData)
            .anomalies(anomalies)
            .volatilityMetrics(volatilityMetrics)
            .reportMarkdown(reportResult.isSuccess() ? reportResult.getOutput() : null)
            .success(true)
            .build();
    }

    private List<String> parseAnomalies(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { return new ArrayList<>(); }
    }

    private Map<String, Object> parseVolatility(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        // Return mutable map so callers can add entries without UnsupportedOperationException
        catch (Exception e) { return new HashMap<>(Map.of("trend", "unknown")); }
    }

    private List<Map<String, Object>> toMapList(com.javacodeagent.core.data.model.DataQueryResult result) {
        if (result == null || result.rows().isEmpty()) return List.of();
        List<String> cols = result.columns();
        return result.rows().stream()
            .map(row -> {
                Map<String, Object> m = new HashMap<>();
                for (int i = 0; i < cols.size() && i < row.size(); i++) m.put(cols.get(i), row.get(i));
                return m;
            })
            .collect(Collectors.toList());
    }

    private Object extractCurrentValue(com.javacodeagent.core.data.model.DataQueryResult result) {
        if (result == null || result.rows().isEmpty()) return null;
        return result.rows().get(0).isEmpty() ? null : result.rows().get(0).get(0);
    }
}
