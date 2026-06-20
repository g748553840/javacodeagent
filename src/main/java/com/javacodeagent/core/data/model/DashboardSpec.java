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

    public int getChartCount() {
        return charts == null ? 0 : charts.size();
    }
}
