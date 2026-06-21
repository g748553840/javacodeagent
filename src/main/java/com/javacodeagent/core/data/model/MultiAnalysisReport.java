package com.javacodeagent.core.data.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 多 Agent 协作分析的聚合报告：
 * DataAnalysisAgent（编排）→ AnomalyDetectorAgent + VolatilityAnalysisAgent（并行）→ ReportGenerationAgent
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiAnalysisReport {

    private String question;

    /** NL2SQL 生成的查询语句 */
    private String sql;

    /** 图表类型（GPT-Vis 协议） */
    private String displayType;

    /** 查询返回的数据行 */
    private List<Map<String, Object>> data;

    /** 实际数据行数 */
    private int rowCount;

    /** AnomalyDetectorAgent 发现的异常列表 */
    private List<String> anomalies;

    /** VolatilityAnalysisAgent 计算的波动指标 */
    private Map<String, Object> volatilityMetrics;

    /** ReportGenerationAgent 生成的 Markdown 综合报告 */
    private String reportMarkdown;

    private boolean success;
    private String errorMessage;

    public static MultiAnalysisReport error(String question, String errorMessage) {
        return MultiAnalysisReport.builder()
            .question(question)
            .success(false)
            .errorMessage(errorMessage)
            .build();
    }
}
