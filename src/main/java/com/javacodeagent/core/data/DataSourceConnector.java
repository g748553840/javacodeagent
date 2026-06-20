package com.javacodeagent.core.data;

import com.javacodeagent.core.data.model.DataQueryResult;

import java.time.Duration;
import java.util.List;

public interface DataSourceConnector {
    String getDialect();
    String getDatabaseName();
    List<String> listTables();
    String getTableInfo(List<String> tableNames);
    DataQueryResult executeQuery(String sql, int maxRows, Duration timeout);
    void close();
}
