package com.javacodeagent.core.data;

import com.javacodeagent.core.data.model.SqlValidationResult;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class SqlValidator {

    private static final Set<String> BLOCKED_KEYWORDS = Set.of(
        "INSERT", "UPDATE", "DELETE", "DROP", "TRUNCATE",
        "ALTER", "CREATE", "GRANT", "REVOKE", "EXEC", "EXECUTE", "MERGE"
    );

    public SqlValidationResult validate(String sql) {
        if (sql == null || sql.isBlank()) {
            return SqlValidationResult.reject("SQL is empty");
        }
        String upper = sql.trim().toUpperCase();
        for (String kw : BLOCKED_KEYWORDS) {
            // 判断是否以关键字开头，或关键字前有空格（防止 "SELECTINSERT" 误报）
            if (upper.startsWith(kw + " ") || upper.startsWith(kw + "\n")
                    || upper.startsWith(kw + "\t") || upper.equals(kw)
                    || upper.contains(" " + kw + " ") || upper.contains("\n" + kw + " ")) {
                return SqlValidationResult.reject("DML/DDL not allowed: " + kw);
            }
        }
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
            return SqlValidationResult.reject("Only SELECT / WITH queries are allowed. Got: " + upper.substring(0, Math.min(30, upper.length())));
        }
        return SqlValidationResult.allow();
    }
}
