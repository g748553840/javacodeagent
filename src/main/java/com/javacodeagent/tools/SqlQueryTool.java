package com.javacodeagent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javacodeagent.core.data.DataAgentConstants;
import com.javacodeagent.core.data.DataSourceConnector;
import com.javacodeagent.core.data.SqlValidator;
import com.javacodeagent.core.data.model.DataQueryResult;
import com.javacodeagent.core.data.model.SqlValidationResult;
import com.javacodeagent.core.enums.PermissionType;
import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.core.model.ToolExecutionResult;
import com.javacodeagent.core.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class SqlQueryTool implements Tool {

    private final DataSourceConnector connector;
    private final SqlValidator sqlValidator;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() { return "SqlQuery"; }

    @Override
    public String getDescription() {
        return "Execute a read-only SQL SELECT query against the configured database. " +
               "Returns JSON array of rows. Only SELECT/WITH statements are allowed.";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "sql", Map.of("type", "string", "description", "The SELECT SQL query to execute"),
                "max_rows", Map.of("type", "integer", "description", "Maximum rows to return (default 200)")
            ),
            "required", List.of("sql")
        );
    }

    @Override
    public boolean requiresPermission() { return true; }

    @Override
    public PermissionType getRequiredPermission() { return PermissionType.DATABASE_READ; }

    @Override
    public boolean isBlocking() { return true; }

    @Override
    public ToolExecutionResult execute(Map<String, Object> input, ExecutionContext context) {
        String sql = (String) input.get("sql");
        int maxRows = input.get("max_rows") instanceof Number n ? n.intValue() : DataAgentConstants.DEFAULT_MAX_ROWS;

        SqlValidationResult valid = sqlValidator.validate(sql);
        if (!valid.isAllowed()) {
            return ToolExecutionResult.error("SQL rejected: " + valid.reason());
        }

        try {
            DataQueryResult result = connector.executeQuery(sql, maxRows, DataAgentConstants.DEFAULT_QUERY_TIMEOUT);
            List<Map<String, Object>> records = toRecords(result);
            String json = objectMapper.writeValueAsString(Map.of(
                "columns", result.columns(),
                "rows", records,
                "total", result.totalRows()
            ));
            log.debug("SqlQueryTool: {} rows returned for: {}", result.totalRows(), sql);
            return ToolExecutionResult.success(json);
        } catch (Exception e) {
            log.error("SqlQueryTool execution failed: {}", e.getMessage());
            return ToolExecutionResult.error("Query failed: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> toRecords(DataQueryResult qr) {
        if (qr.columns().isEmpty()) return List.of();
        return qr.rows().stream()
            .map(row -> {
                Map<String, Object> record = new LinkedHashMap<>();
                for (int i = 0; i < qr.columns().size(); i++) {
                    record.put(qr.columns().get(i), i < row.size() ? row.get(i) : null);
                }
                return record;
            })
            .collect(Collectors.toList());
    }
}
