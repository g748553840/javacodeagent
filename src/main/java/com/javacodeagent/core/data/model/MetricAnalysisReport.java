package com.javacodeagent.core.data.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 指标归因分析报告。
 *
 * <p>由 {@code POST /api/v1/data-agent/metric-analysis} 端点返回，
 * 整合指标元数据 + 异常检测 + 波动分析 + 综合 Markdown 报告。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricAnalysisReport {

    /** 分析的指标名称。 */
    private String metricName;

    /** 指标展示名称。 */
    private String displayName;

    /** 查询历史天数。 */
    private int lookbackDays;

    /** 候选分析维度。 */
    private List<String> dimensions;

    /** 当前指标值（聚合结果）。 */
    private Object currentValue;

    /** 历史趋势数据（时序行列表）。 */
    private List<Map<String, Object>> historicalData;

    /** AnomalyDetectorAgent 检测到的异常列表。 */
    private List<String> anomalies;

    /** VolatilityAnalysisAgent 计算的波动指标。 */
    private Map<String, Object> volatilityMetrics;

    /** ReportGenerationAgent 生成的 Markdown 归因报告。 */
    private String reportMarkdown;

    private boolean success;
    private String errorMessage;

    public static MetricAnalysisReport error(String metricName, String errorMessage) {
        return MetricAnalysisReport.builder()
            .metricName(metricName)
            .success(false)
            .errorMessage(errorMessage)
            .build();
    }
}
