package com.javacodeagent.core.data.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardSpec {
    private String title;
    private List<ChartSpec> charts;
    @Builder.Default
    private String displayStrategy = "default";
    /**
     * LLM 输出解析失败或未产出任何图表定义时的错误说明；正常情况下为 null。
     * 存在此字段且 charts 为空时，前端应展示错误而非把"零图表"误当作
     * "LLM 认为该问题无需图表"的正常结果。
     */
    private String errMsg;

    public int getChartCount() {
        return charts == null ? 0 : charts.size();
    }
}
