package com.javacodeagent.core.data;

import com.javacodeagent.core.data.model.DataQueryResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class JdbcDataSourceConnector implements DataSourceConnector {

    private final JdbcTemplate jdbc;
    private final String dialect;
    private final String dbName;

    public JdbcDataSourceConnector(JdbcTemplate jdbc, String dialect, String dbName) {
        this.jdbc = jdbc;
        this.dialect = dialect.toLowerCase();
        this.dbName = dbName;
    }

    @Override
    public String getDialect() { return dialect; }

    @Override
    public String getDatabaseName() { return dbName; }

    @Override
    public List<String> listTables() {
        String sql = switch (dialect) {
            case "mysql" -> "SELECT TABLE_NAME FROM information_schema.TABLES " +
                           "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'";
            case "postgresql" -> "SELECT tablename FROM pg_tables WHERE schemaname = 'public'";
            default -> // h2, sqlite
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE'";
        };
        try {
            return jdbc.queryForList(sql, String.class);
        } catch (Exception e) {
            log.warn("Failed to list tables: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public String getTableInfo(List<String> tableNames) {
        StringBuilder sb = new StringBuilder();
        for (String table : tableNames) {
            try {
                sb.append(buildCreateTableDdl(table)).append("\n\n");
                sb.append("/*\n").append(DataAgentConstants.DEFAULT_SAMPLE_ROWS)
                  .append(" rows from ").append(table).append(":\n");
                sb.append(buildSampleRows(table));
                sb.append("*/\n\n");
            } catch (Exception e) {
                log.warn("Error reading table {}: {}", table, e.getMessage());
                sb.append("/* Error reading table ").append(table)
                  .append(": ").append(e.getMessage()).append(" */\n\n");
            }
        }
        return sb.toString();
    }

    @Override
    public DataQueryResult executeQuery(String sql, int maxRows, Duration timeout) {
        String limitedSql = appendLimit(sql, maxRows);
        int timeoutSeconds = timeout != null ? (int) Math.max(1, timeout.getSeconds()) : (int) DataAgentConstants.DEFAULT_QUERY_TIMEOUT.getSeconds();
        try {
            List<Map<String, Object>> result = jdbc.execute(
                con -> {
                    java.sql.PreparedStatement ps = con.prepareStatement(limitedSql);
                    ps.setQueryTimeout(timeoutSeconds);
                    return ps;
                },
                ps -> {
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        java.sql.ResultSetMetaData meta = rs.getMetaData();
                        int colCount = meta.getColumnCount();
                        List<String> labels = new ArrayList<>();
                        for (int i = 1; i <= colCount; i++) labels.add(meta.getColumnLabel(i));
                        List<Map<String, Object>> rows = new ArrayList<>();
                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= colCount; i++) row.put(labels.get(i - 1), rs.getObject(i));
                            rows.add(row);
                        }
                        return rows;
                    }
                }
            );
            if (result == null || result.isEmpty()) return DataQueryResult.empty(sql);
            List<String> columns = new ArrayList<>(result.get(0).keySet());
            List<List<Object>> data = result.stream()
                .map(r -> new ArrayList<Object>(r.values()))
                .collect(Collectors.toList());
            return new DataQueryResult(columns, data, data.size(), sql);
        } catch (Exception e) {
            throw new RuntimeException("Query failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        // JdbcTemplate is managed by Spring; no explicit close needed
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private String buildCreateTableDdl(String table) {
        List<Map<String, Object>> cols = getColumnsInfo(table);
        if (cols.isEmpty()) return "-- Table " + table + " (no columns found)";
        String colDefs = cols.stream()
            .map(c -> "  \"" + c.get("COLUMN_NAME") + "\" " + c.get("TYPE_NAME"))
            .collect(Collectors.joining(",\n"));
        return "CREATE TABLE \"" + table + "\" (\n" + colDefs + "\n)";
    }

    private List<Map<String, Object>> getColumnsInfo(String table) {
        return switch (dialect) {
            case "mysql" -> jdbc.queryForList(
                "SELECT COLUMN_NAME, COLUMN_TYPE AS TYPE_NAME FROM information_schema.COLUMNS " +
                "WHERE TABLE_NAME = ? AND TABLE_SCHEMA = DATABASE() ORDER BY ORDINAL_POSITION", table);
            case "postgresql" -> jdbc.queryForList(
                "SELECT column_name AS COLUMN_NAME, data_type AS TYPE_NAME " +
                "FROM information_schema.columns WHERE table_name = ? ORDER BY ordinal_position", table);
            default -> // h2
                jdbc.queryForList(
                "SELECT COLUMN_NAME, TYPE_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                "WHERE TABLE_NAME = ? ORDER BY ORDINAL_POSITION", table.toUpperCase());
        };
    }

    private String buildSampleRows(String table) {
        try {
            String q = switch (dialect) {
                case "mysql" -> "SELECT * FROM `" + table + "` LIMIT " + DataAgentConstants.DEFAULT_SAMPLE_ROWS;
                case "postgresql" -> "SELECT * FROM \"" + table + "\" LIMIT " + DataAgentConstants.DEFAULT_SAMPLE_ROWS;
                default -> "SELECT * FROM \"" + table + "\" LIMIT " + DataAgentConstants.DEFAULT_SAMPLE_ROWS;
            };
            List<Map<String, Object>> rows = jdbc.queryForList(q);
            if (rows.isEmpty()) return "(empty table)\n";
            String header = String.join("\t", rows.get(0).keySet());
            String dataRows = rows.stream()
                .map(r -> r.values().stream().map(String::valueOf).collect(Collectors.joining("\t")))
                .collect(Collectors.joining("\n"));
            return header + "\n" + dataRows + "\n";
        } catch (Exception e) {
            return "(error reading sample: " + e.getMessage() + ")\n";
        }
    }

    /**
     * 仅在最外层（括号深度为 0）没有 LIMIT 子句时才追加，
     * 避免误判子查询中的 LIMIT（如 SELECT * FROM (SELECT id LIMIT 10) sub）。
     * 支持空格、换行、制表符等空白符前导的 LIMIT 关键字。
     */
    private String appendLimit(String sql, int maxRows) {
        String trimmed = sql.trim();
        if (hasOuterLimit(trimmed.toUpperCase())) return trimmed;
        return trimmed + " LIMIT " + maxRows;
    }

    private boolean hasOuterLimit(String upper) {
        int depth = 0;
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && Character.isWhitespace(c)
                    && upper.regionMatches(i + 1, "LIMIT ", 0, 6)) {
                // Whitespace before LIMIT at the outermost level — LIMIT clause exists
                return true;
            }
        }
        return false;
    }
}
