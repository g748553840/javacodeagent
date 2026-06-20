package com.javacodeagent.core.data;

import com.javacodeagent.core.data.model.DataQueryResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 将 Excel / CSV 字节流载入 H2 内存表，随后通过 JdbcDataSourceConnector 用 NL2SQL 分析。
 * 每次 importFile() 生成唯一表名，返回给调用方用于后续查询。
 */
@Slf4j
@Service
public class ExcelDataSourceConnector {

    private static final Pattern UNSAFE = Pattern.compile("[^a-zA-Z0-9]");

    private final JdbcTemplate jdbc;

    public ExcelDataSourceConnector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 将文件导入 H2 内存表，返回表名。
     * 支持 .xlsx / .csv 格式。
     */
    public String importFile(byte[] bytes, String filename) throws IOException {
        String tableName = "tbl_" + Long.toHexString(System.nanoTime()).substring(0, 8);
        if (filename.endsWith(".xlsx") || filename.endsWith(".xls")) {
            importExcel(bytes, tableName);
        } else if (filename.endsWith(".csv")) {
            importCsv(bytes, tableName);
        } else {
            throw new IllegalArgumentException("Unsupported file type: " + filename + ". Use .xlsx or .csv");
        }
        log.info("Imported '{}' into table '{}' (H2)", filename, tableName);
        return tableName;
    }

    /**
     * 获取已导入表的 Schema 信息（供 Nl2SqlService 构建 Prompt）。
     */
    public String getTableInfo(String tableName) {
        try {
            List<String> columns = getColumns(tableName);
            List<String> sampleRows = getSampleRows(tableName, 3);
            StringBuilder sb = new StringBuilder();
            sb.append("CREATE TABLE \"").append(tableName).append("\" (\n");
            columns.forEach(c -> sb.append("  \"").append(c).append("\" VARCHAR(1000),\n"));
            if (!columns.isEmpty()) sb.setLength(sb.length() - 2);
            sb.append("\n)\n\n/* 3 rows from ").append(tableName).append(":\n");
            sb.append(String.join("\n", sampleRows)).append("\n*/\n");
            return sb.toString();
        } catch (Exception e) {
            return "/* Error reading table " + tableName + ": " + e.getMessage() + " */\n";
        }
    }

    /**
     * 执行针对已导入表的 SELECT 查询。
     */
    public DataQueryResult query(String sql, int maxRows) {
        try {
            String limitedSql = sql.trim().toUpperCase().contains(" LIMIT ")
                ? sql.trim() : sql.trim() + " LIMIT " + maxRows;
            List<java.util.Map<String, Object>> rows = jdbc.queryForList(limitedSql);
            if (rows.isEmpty()) return DataQueryResult.empty(sql);
            List<String> cols = new ArrayList<>(rows.get(0).keySet());
            List<List<Object>> data = rows.stream()
                .map(r -> new ArrayList<Object>(r.values()))
                .collect(Collectors.toList());
            return new DataQueryResult(cols, data, data.size(), sql);
        } catch (Exception e) {
            throw new RuntimeException("Query failed: " + e.getMessage(), e);
        }
    }

    // ── Excel ────────────────────────────────────────────────────────────────

    private void importExcel(byte[] bytes, String tableName) throws IOException {
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            Iterator<Row> rowIt = sheet.iterator();
            if (!rowIt.hasNext()) return;

            DataFormatter formatter = new DataFormatter();
            Row headerRow = rowIt.next();
            List<String> headers = new ArrayList<>();
            for (Cell cell : headerRow) {
                headers.add(toSafeColName(formatter.formatCellValue(cell)));
            }

            createTable(tableName, headers);

            while (rowIt.hasNext()) {
                Row row = rowIt.next();
                List<Object> values = new ArrayList<>();
                for (int i = 0; i < headers.size(); i++) {
                    Cell cell = row.getCell(i, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    values.add(cell.getCellType() == CellType.BLANK ? null : formatter.formatCellValue(cell));
                }
                insertRow(tableName, headers, values);
            }
        }
    }

    // ── CSV ──────────────────────────────────────────────────────────────────

    private void importCsv(byte[] bytes, String tableName) throws IOException {
        try (CSVParser parser = CSVParser.parse(
                new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8),
                CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {

            List<String> rawHeaders = parser.getHeaderNames();
            List<String> headers = rawHeaders.stream().map(this::toSafeColName).collect(Collectors.toList());
            createTable(tableName, headers);

            for (CSVRecord record : parser) {
                List<Object> values = new ArrayList<>();
                for (String h : rawHeaders) {
                    String v = record.get(h);
                    values.add(v == null || v.isBlank() ? null : v);
                }
                insertRow(tableName, headers, values);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void createTable(String tableName, List<String> colNames) {
        String cols = colNames.stream()
            .map(c -> "\"" + c + "\" VARCHAR(1000)")
            .collect(Collectors.joining(", "));
        jdbc.execute("CREATE TABLE IF NOT EXISTS \"" + tableName + "\" (" + cols + ")");
    }

    private void insertRow(String tableName, List<String> colNames, List<Object> values) {
        String cols = colNames.stream().map(c -> "\"" + c + "\"").collect(Collectors.joining(", "));
        String placeholders = colNames.stream().map(c -> "?").collect(Collectors.joining(", "));
        jdbc.update("INSERT INTO \"" + tableName + "\" (" + cols + ") VALUES (" + placeholders + ")", values.toArray());
    }

    private List<String> getColumns(String tableName) {
        return jdbc.queryForList(
            "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? ORDER BY ORDINAL_POSITION",
            String.class, tableName.toUpperCase());
    }

    private List<String> getSampleRows(String tableName, int n) {
        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM \"" + tableName + "\" LIMIT " + n);
        return rows.stream()
            .map(r -> r.values().stream().map(String::valueOf).collect(Collectors.joining("\t")))
            .collect(Collectors.toList());
    }

    private String toSafeColName(String name) {
        if (name == null || name.isBlank()) return "col_" + System.nanoTime();
        String safe = UNSAFE.matcher(name.trim().replace(" ", "_")).replaceAll("_");
        safe = safe.replaceAll("_+", "_").replaceAll("^_|_$", "");
        return safe.isBlank() ? "col" : safe.substring(0, Math.min(60, safe.length()));
    }
}
