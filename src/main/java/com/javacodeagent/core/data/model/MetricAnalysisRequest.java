package com.javacodeagent.core.data.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 指标归因分析请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricAnalysisRequest {
    /** 指标名称（必须已在 MetricInfoRetriever 中注册）。 */
    private String metricName;

    /** 查询历史天数（默认 30）。 */
    @Builder.Default
    private int lookbackDays = 30;
}
