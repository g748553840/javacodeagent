package com.javacodeagent.core.data.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javacodeagent.core.agent.Agent;
import com.javacodeagent.core.agent.AgentContext;
import com.javacodeagent.core.agent.AgentResult;
import com.javacodeagent.core.agent.AgentTask;
import com.javacodeagent.core.data.model.DataAnalysisReport;
import com.javacodeagent.core.data.model.MultiAnalysisReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * 多 Agent 协作分析编排器。
 *
 * <p>接收 {@link DataAnalysisReport}（来自 NL2SQL 管道），然后：
 * <ol>
 *   <li>并行派发 {@link AnomalyDetectorAgent} 和 {@link VolatilityAnalysisAgent}</li>
 *   <li>聚合两路结果，调用 {@link ReportGenerationAgent} 生成综合 Markdown 报告</li>
 *   <li>返回 {@link MultiAnalysisReport} 序列化为 JSON 字符串（via {@code AgentResult.output}）</li>
 * </ol>
 *
 * <p>并行执行使用 Java 21 虚拟线程，不阻塞 Netty IO 线程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataAnalysisAgent implements Agent {

    private final AnomalyDetectorAgent anomalyDetector;
    private final VolatilityAnalysisAgent volatilityAnalyzer;
    private final ReportGenerationAgent reportGenerator;
    private final ObjectMapper objectMapper;

    @Override
    public String getType() { return "data-analysis"; }

    @Override
    public String getDescription() {
        return "Orchestrates multi-agent data analysis: anomaly detection + volatility analysis + report generation";
    }

    @Override
    public List<String> getAvailableTools() { return List.of(); }

    @Override
    public AgentResult process(AgentTask task, AgentContext context) {
        DataAnalysisReport baseReport = (DataAnalysisReport) task.getParameters().get("report");
        String question = (String) task.getParameters().getOrDefault("question", "");

        if (baseReport == null || !baseReport.isSuccess()) {
            return AgentResult.builder()
                .success(false)
                .error("No valid base report to analyze")
                .build();
        }

        String dataSample = buildDataSample(baseReport);
        String dataSummary = buildDataSummary(baseReport);

        Map<String, Object> sharedParams = Map.of(
            "question", question,
            "dataSample", dataSample
        );

        AgentTask anomalyTask = AgentTask.builder()
            .type(anomalyDetector.getType())
            .description("Detect anomalies in: " + question)
            .parameters(sharedParams)
            .build();

        AgentTask volatilityTask = AgentTask.builder()
            .type(volatilityAnalyzer.getType())
            .description("Analyze volatility in: " + question)
            .parameters(sharedParams)
            .build();

        // Run anomaly detection and volatility analysis in parallel via virtual threads
        AgentResult anomalyResult;
        AgentResult volatilityResult;
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<AgentResult> anomalyFuture = vt.submit(
                () -> anomalyDetector.process(anomalyTask, context));
            Future<AgentResult> volatilityFuture = vt.submit(
                () -> volatilityAnalyzer.process(volatilityTask, context));
            anomalyResult = anomalyFuture.get();
            volatilityResult = volatilityFuture.get();
        } catch (Exception e) {
            log.error("Parallel agent execution failed", e);
            return AgentResult.builder().success(false).error(e.getMessage()).build();
        }

        String anomaliesJson = anomalyResult.isSuccess() ? anomalyResult.getOutput() : "[]";
        String volatilityJson = volatilityResult.isSuccess() ? volatilityResult.getOutput() : "{}";

        // Generate final report — use HashMap to avoid Map.of() NPE on null values
        Map<String, Object> reportParams = new HashMap<>();
        reportParams.put("question", question);
        reportParams.put("dataSummary", dataSummary);
        reportParams.put("anomalies", anomaliesJson != null ? anomaliesJson : "[]");
        reportParams.put("volatility", volatilityJson != null ? volatilityJson : "{}");
        AgentTask reportTask = AgentTask.builder()
            .type(reportGenerator.getType())
            .description("Generate report for: " + question)
            .parameters(reportParams)
            .build();
        AgentResult reportResult = reportGenerator.process(reportTask, context);

        // Build final MultiAnalysisReport
        try {
            List<String> anomalies = parseAnomalies(anomaliesJson);
            Map<String, Object> volatilityMetrics = parseVolatility(volatilityJson);
            List<Map<String, Object>> data = buildDataList(baseReport);

            MultiAnalysisReport report = MultiAnalysisReport.builder()
                .question(question)
                .sql(baseReport.getChartSpec() != null ? baseReport.getChartSpec().getSql() : null)
                .displayType(baseReport.getChartSpec() != null ? baseReport.getChartSpec().getDisplayType() : null)
                .data(data)
                .rowCount(data.size())
                .anomalies(anomalies)
                .volatilityMetrics(volatilityMetrics)
                .reportMarkdown(reportResult.isSuccess() ? reportResult.getOutput() : null)
                .success(true)
                .build();

            return AgentResult.builder()
                .success(true)
                .output(objectMapper.writeValueAsString(report))
                .build();

        } catch (Exception e) {
            log.error("Failed to build MultiAnalysisReport", e);
            return AgentResult.builder().success(false).error(e.getMessage()).build();
        }
    }

    private String buildDataSample(DataAnalysisReport report) {
        if (report.getChartSpec() == null || report.getChartSpec().getData() == null) return "（无数据）";
        List<Map<String, Object>> rows = report.getChartSpec().getData();
        int limit = Math.min(50, rows.size());
        StringBuilder sb = new StringBuilder();
        if (!rows.isEmpty()) {
            sb.append(String.join("\t", rows.get(0).keySet())).append("\n");
        }
        rows.subList(0, limit).forEach(row ->
            sb.append(row.values().stream().map(String::valueOf).collect(Collectors.joining("\t")))
              .append("\n")
        );
        return sb.toString();
    }

    private String buildDataSummary(DataAnalysisReport report) {
        if (report.getChartSpec() == null) return "（无数据）";
        var chart = report.getChartSpec();
        List<Map<String, Object>> data = chart.getData();
        int rows = data != null ? data.size() : 0;
        String cols = data != null && !data.isEmpty()
            ? String.join(", ", data.get(0).keySet())
            : "（无列信息）";
        return "SQL: " + chart.getSql()
            + "\nTotal rows: " + rows
            + "\nColumns: " + cols
            + "\nThought: " + chart.getThought();
    }

    private List<Map<String, Object>> buildDataList(DataAnalysisReport report) {
        if (report.getChartSpec() == null || report.getChartSpec().getData() == null) return List.of();
        return report.getChartSpec().getData();
    }

    @SuppressWarnings("unchecked")
    private List<String> parseAnomalies(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseVolatility(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of("trend", "unknown");
        }
    }
}
