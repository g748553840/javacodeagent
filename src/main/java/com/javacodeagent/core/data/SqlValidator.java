package com.javacodeagent.core.data;

import com.javacodeagent.core.data.model.SqlValidationResult;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class SqlValidator {

    /** SQL 校验错误提示中截取的最大前缀长度。 */
    private static final int ERROR_EXCERPT_LENGTH = DataAgentConstants.SQL_ERROR_EXCERPT_LENGTH;

    // 有序列表，保证拒绝原因中关键词名称的确定性
    private static final List<String> BLOCKED_KEYWORDS = List.of(
        "INSERT", "UPDATE", "DELETE", "DROP", "TRUNCATE",
        "ALTER", "CREATE", "GRANT", "REVOKE", "EXEC", "EXECUTE", "MERGE"
    );

    // 去除字符串字面量（含 '' 转义），防止 WHERE status='DELETE' 被误报
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");

    // 去除行注释（-- ...）
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\r\n]*");

    // 去除块注释（/* ... */），非贪婪
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    // 类初始化时一次性预编译所有关键字 Pattern，避免每次 validate() 调用都重复 compile
    private static final Map<String, Pattern> KW_PATTERNS;
    static {
        Map<String, Pattern> m = new LinkedHashMap<>();
        for (String kw : BLOCKED_KEYWORDS) {
            // 词边界：关键字前后不能是字母/数字/下划线，防止误拦 deleted_at、execute_flag 等列名
            m.put(kw, Pattern.compile("(?<![A-Z0-9_])" + kw + "(?![A-Z0-9_])"));
        }
        KW_PATTERNS = Collections.unmodifiableMap(m);
    }

    public SqlValidationResult validate(String sql) {
        if (sql == null || sql.isBlank()) {
            return SqlValidationResult.reject("SQL is empty");
        }
        String upper = sql.trim().toUpperCase();

        // 前缀检查是最快的拒绝路径，先做
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
            return SqlValidationResult.reject(
                "Only SELECT / WITH queries are allowed. Got: " + upper.substring(0, Math.min(ERROR_EXCERPT_LENGTH, upper.length())));
        }

        // 分号检测：防止堆叠查询（PostgreSQL/H2 支持 multi-statement）
        // 先剥离字符串字面量和注释再检测，避免合法的 ';' 字符串误报
        String noComments = BLOCK_COMMENT.matcher(upper).replaceAll(" ");
        noComments = LINE_COMMENT.matcher(noComments).replaceAll(" ");
        String stripped = STRING_LITERAL.matcher(noComments).replaceAll(" ");

        if (stripped.contains(";")) {
            return SqlValidationResult.reject("Stacked queries (semicolons) are not allowed");
        }

        // 关键字边界匹配（在已剥离字符串/注释的文本上检查）
        for (Map.Entry<String, Pattern> entry : KW_PATTERNS.entrySet()) {
            if (entry.getValue().matcher(stripped).find()) {
                return SqlValidationResult.reject("DML/DDL not allowed: " + entry.getKey());
            }
        }

        return SqlValidationResult.allow();
    }
}
