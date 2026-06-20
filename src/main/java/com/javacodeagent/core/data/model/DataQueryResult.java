package com.javacodeagent.core.data.model;

import java.util.List;

public record DataQueryResult(
    List<String> columns,
    List<List<Object>> rows,
    int totalRows,
    String sql
) {
    public static DataQueryResult empty(String sql) {
        return new DataQueryResult(List.of(), List.of(), 0, sql);
    }
}
