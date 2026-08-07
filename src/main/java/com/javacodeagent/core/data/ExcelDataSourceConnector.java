package com.javacodeagent.core.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javacodeagent.core.data.model.DataQueryResult;
import com.javacodeagent.core.enums.MessageType;
import com.javacodeagent.core.enums.PermissionLevel;
import com.javacodeagent.core.llm.LLMClient;
import com.javacodeagent.core.model.ConversationContext;
import com.javacodeagent.core.model.Message;
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
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 将 Excel / CSV 字节流载入 H2 内存表，随后通过 JdbcDataSourceConnector 用 NL2SQL 分析。
 * 每次 importFile() 生成唯一表名，返回给调用方用于后续查询。
 *
 * <p>中文列名翻译：若检测到表头含有 CJK 字符，自动调用 LLM 将中文表头转换为
 * snake_case 英文列名（如 "销售额" → "sales_amount"），提升 NL2SQL 生成质量。
 */
@Slf4j
@Service
public class ExcelDataSourceConnector {

    private static final Pattern UNSAFE = Pattern.compile("[^a-zA-Z0-9]");

    // 内部生成的表名固定格式为 "tbl_" + 12 位十六进制 UUID 片段（见 importFile）。
    // getTableInfo/getSampleRows 的 tableName 直接来自客户端请求体（未经 SqlValidator），
    // 必须先校验格式再拼接进 SQL，否则可构造出如
    // {"tableName":"x\" UNION SELECT ... --"} 的标识符注入攻击。
    private static final Pattern VALID_TABLE_NAME =
        Pattern.compile("^tbl_[a-f0-9]{" + DataAgentConstants.TABLE_NAME_UUID_LENGTH + "}$");

    private final JdbcTemplate jdbc;
    private final LLMClient llmClient;
    private final ObjectMapper objectMapper;

    public ExcelDataSourceConnector(JdbcTemplate jdbc, LLMClient llmClient, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    private void validateTableName(String tableName) {
        if (tableName == null || !VALID_TABLE_NAME.matcher(tableName).matches()) {
            throw new IllegalArgumentException("Invalid table name: " + tableName);
        }
    }

    /**
     * 将文件导入 H2 内存表，返回表名。
     * 支持 .xlsx / .csv 格式。
     *
     * <p>{@code @Transactional} 放在此公共入口方法上，确保 Spring AOP 代理能够拦截：
     * Spring 事务代理只能拦截经过代理调用的 public 方法；私有方法上的 @Transactional
     * 会被静默忽略，无法实现回滚。
     */
    @Transactional
    public String importFile(byte[] bytes, String filename) throws IOException {
        // UUID 保证唯一性，避免高并发下 nanoTime 截断后 8 位碰撞导致数据混入同一张表
        String tableName = "tbl_" + UUID.randomUUID().toString().replace("-", "").substring(0, DataAgentConstants.TABLE_NAME_UUID_LENGTH);
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
            validateTableName(tableName);
            List<String> columns = getColumns(tableName);
            List<String> sampleRows = getSampleRows(tableName, DataAgentConstants.DEFAULT_SAMPLE_ROWS);
            StringBuilder sb = new StringBuilder();
            sb.append("CREATE TABLE \"").append(tableName).append("\" (\n");
            columns.forEach(c -> sb.append("  \"").append(c).append("\" VARCHAR(1000),\n"));
            if (!columns.isEmpty()) sb.setLength(sb.length() - 2);
            sb.append("\n)\n\n/* ").append(DataAgentConstants.DEFAULT_SAMPLE_ROWS)
              .append(" rows from ").append(tableName).append(":\n");
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
            String trimmed = sql.trim();
            // 使用深度感知检测，避免 "\nLIMIT"、"\tLIMIT" 被漏判，
            // 同时防止子查询中的 LIMIT 被误判为外层 LIMIT
            String limitedSql = hasOuterLimit(trimmed.toUpperCase()) ? trimmed : trimmed + " LIMIT " + maxRows;
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
            List<String> rawHeaders = new ArrayList<>();
            for (Cell cell : headerRow) {
                rawHeaders.add(formatter.formatCellValue(cell));
            }

            // Translate Chinese headers to snake_case English if CJK chars detected
            List<String> translatedHeaders = translateChineseHeaders(rawHeaders);
            List<String> headers = translatedHeaders.stream()
                .map(this::toSafeColName)
                .collect(Collectors.toList());

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
            // Translate Chinese headers to snake_case English if CJK chars detected
            List<String> translatedHeaders = translateChineseHeaders(rawHeaders);
            List<String> headers = translatedHeaders.stream()
                .map(this::toSafeColName)
                .collect(Collectors.toList());
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

    // ── Chinese Header Translation ────────────────────────────────────────────

    /**
     * 检测表头中是否含有 CJK（中文/日文/韩文）字符。
     * 覆盖 CJK 统一汉字（U+4E00–U+9FFF）和扩展区 A（U+3400–U+4DBF）。
     */
    private boolean hasCjkChars(String s) {
        return s != null && s.codePoints().anyMatch(cp ->
            (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF));
    }

    /**
     * 若列名列表中含有 CJK 字符，则调用 LLM 批量翻译为 snake_case 英文列名。
     * 翻译失败时降级返回原始列名列表，不中断导入流程。
     *
     * <p>此方法在 {@code Schedulers.boundedElastic()} 线程上执行（由 ExcelAnalysisController 保证），
     * 因此可安全调用 {@code Mono.block()}。
     */
    private List<String> translateChineseHeaders(List<String> rawHeaders) {
        if (rawHeaders == null || rawHeaders.isEmpty()) return rawHeaders;
        boolean hasCjk = rawHeaders.stream().anyMatch(this::hasCjkChars);
        if (!hasCjk) return rawHeaders;

        log.info("Detected CJK column names, translating via LLM: {}", rawHeaders);
        String prompt = """
            Translate these database column names to snake_case English.
            Input column names: %s
            Rules:
            1. Output ONLY a JSON array with the same number of elements in the same order
            2. Each element must be a valid snake_case identifier (lowercase, underscores, no spaces)
            3. Preserve the semantic meaning
            Example: ["销售额", "地区", "日期"] → ["sales_amount", "region", "date"]
            Respond ONLY with the JSON array, no other text.
            """.formatted(rawHeaders);

        ConversationContext ctx = ConversationContext.builder()
            .conversationId("col-translate-" + UUID.randomUUID())
            .userId(DataAgentConstants.SYSTEM_USER_ID)
            .permissionLevel(PermissionLevel.READ_ONLY)
            .messages(List.of(
                Message.builder().type(MessageType.USER).content(prompt).build()
            ))
            .build();

        try {
            var resp = llmClient.chat(ctx).block();
            String response = (resp != null) ? resp.getContent() : null;
            if (response == null) throw new IllegalArgumentException("LLM returned null response");
            // Extract JSON array from response
            int start = response.indexOf('[');
            int end = response.lastIndexOf(']');
            if (start == -1 || end == -1) throw new IllegalArgumentException("No JSON array in response");
            List<String> translated = objectMapper.readValue(
                response.substring(start, end + 1),
                new TypeReference<List<String>>() {});
            if (translated.size() != rawHeaders.size()) {
                throw new IllegalArgumentException("Translated count mismatch: expected "
                    + rawHeaders.size() + " got " + translated.size());
            }
            log.info("Column translation: {} → {}", rawHeaders, translated);
            return translated;
        } catch (Exception e) {
            log.warn("LLM column translation failed, falling back to raw headers: {}", e.getMessage());
            return rawHeaders;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void createTable(String tableName, List<String> colNames) {
        String cols = colNames.stream()
            .map(c -> "\"" + c + "\" VARCHAR(" + DataAgentConstants.IMPORT_VARCHAR_LENGTH + ")")
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
            String.class, tableName);
    }

    private List<String> getSampleRows(String tableName, int n) {
        // SQL 标识符无法用 "?" 参数化，必须在拼接前显式校验格式，
        // 防止调用方（如 getTableInfo）遗漏校验时在此处发生标识符注入
        validateTableName(tableName);
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
        return safe.isBlank() ? "col" : safe.substring(0, Math.min(DataAgentConstants.MAX_COLUMN_NAME_LENGTH, safe.length()));
    }

    /**
     * 仅在最外层（括号深度为 0）没有 LIMIT 子句时返回 false。
     * 支持空格、换行、制表符等空白符前导的 LIMIT 关键字，
     * 避免误判子查询中的 LIMIT（如 SELECT * FROM (SELECT id LIMIT 10) sub）。
     */
    private boolean hasOuterLimit(String upper) {
        int depth = 0;
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && Character.isWhitespace(c)
                    && upper.regionMatches(i + 1, "LIMIT ", 0, DataAgentConstants.LIMIT_KEYWORD_LENGTH)) {
                return true;
            }
        }
        return false;
    }
}
