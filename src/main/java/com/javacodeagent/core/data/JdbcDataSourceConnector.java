package com.javacodeagent.core.data;

import com.javacodeagent.core.data.model.DataQueryResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class JdbcDataSourceConnector implements DataSourceConnector {

    private static final int DEFAULT_SAMPLE_ROWS = 3;

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
                sb.append("/*\n").append(DEFAULT_SAMPLE_ROWS)
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
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(limitedSql);
            if (rows.isEmpty()) return DataQueryResult.empty(sql);

            List<String> columns = new ArrayList<>(rows.get(0).keySet());
            List<List<Object>> data = rows.stream()
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
                case "mysql", "postgresql" -> "SELECT * FROM `" + table + "` LIMIT " + DEFAULT_SAMPLE_ROWS;
                default -> "SELECT * FROM \"" + table + "\" LIMIT " + DEFAULT_SAMPLE_ROWS;
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

    private String appendLimit(String sql, int maxRows) {
        String trimmed = sql.trim();
        String upper = trimmed.toUpperCase();
        if (upper.contains(" LIMIT ")) return trimmed;
        return trimmed + " LIMIT " + maxRows;
    }
}
