package com.javacodeagent.core.data.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChartSpec {
    private String sql;
    private String displayType;
    private String title;
    private String thought;
    private List<Map<String, Object>> data;
    private String errMsg;

    public static ChartSpec from(Nl2SqlResult nl2sql, DataQueryResult queryResult) {
        return ChartSpec.builder()
            .sql(nl2sql.getSql())
            .displayType(nl2sql.getDisplayType())
            .thought(nl2sql.getThought())
            .title("")
            .data(queryResult != null ? toRecords(queryResult) : List.of())
            .build();
    }

    private static List<Map<String, Object>> toRecords(DataQueryResult qr) {
        if (qr.columns().isEmpty()) return List.of();
        return qr.rows().stream()
            .map(row -> {
                java.util.Map<String, Object> record = new java.util.LinkedHashMap<>();
                for (int i = 0; i < qr.columns().size(); i++) {
                    record.put(qr.columns().get(i), i < row.size() ? row.get(i) : null);
                }
                return (Map<String, Object>) record;
            })
            .toList();
    }
}
