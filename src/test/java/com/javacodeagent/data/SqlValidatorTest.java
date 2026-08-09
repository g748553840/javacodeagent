package com.javacodeagent.data;

import com.javacodeagent.core.data.SqlValidator;
import com.javacodeagent.core.data.model.SqlValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SqlValidator 单元测试。
 * 覆盖：白名单正常路径、DML 拦截、DDL 拦截、WITH CTE 放行、空 SQL 拒绝。
 */
class SqlValidatorTest {

    private SqlValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SqlValidator();
    }

    // ── 正常放行 ──────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT * FROM orders",
        "select count(*) from users",
        "SELECT a, b FROM t WHERE id = 1 ORDER BY a LIMIT 10",
        "WITH cte AS (SELECT 1) SELECT * FROM cte",
        "with cte as (select id from orders) select * from cte"
    })
    void allowedQueries(String sql) {
        SqlValidationResult result = validator.validate(sql);
        assertThat(result.isAllowed())
            .as("Expected SQL to be allowed: %s", sql)
            .isTrue();
    }

    // ── DML/DDL 拦截 ─────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "INSERT INTO orders VALUES (1,'a')",
        "UPDATE orders SET status='done' WHERE id=1",
        "DELETE FROM orders WHERE id=1",
        "DROP TABLE orders",
        "TRUNCATE TABLE orders",
        "ALTER TABLE orders ADD COLUMN foo INT",
        "CREATE TABLE foo (id INT)",
        "GRANT ALL ON orders TO admin",
        "REVOKE SELECT ON orders FROM guest",
        "EXEC sp_help",
        "EXECUTE sp_help",
        "MERGE INTO orders USING src ON orders.id=src.id WHEN MATCHED THEN DELETE"
    })
    void blockedDmlDdl(String sql) {
        SqlValidationResult result = validator.validate(sql);
        assertThat(result.isAllowed())
            .as("Expected SQL to be blocked: %s", sql)
            .isFalse();
        assertThat(result.reason()).isNotBlank();
    }

    // ── 非 SELECT/WITH 前缀 ───────────────────────────────────────────────────

    @Test
    void nonSelectPrefix_isRejected() {
        SqlValidationResult result = validator.validate("SHOW TABLES");
        assertThat(result.isAllowed()).isFalse();
        assertThat(result.reason()).contains("Only SELECT");
    }

    // ── 空 SQL ────────────────────────────────────────────────────────────────

    @Test
    void nullSql_isRejected() {
        assertThat(validator.validate(null).isAllowed()).isFalse();
    }

    @Test
    void blankSql_isRejected() {
        assertThat(validator.validate("   ").isAllowed()).isFalse();
    }

    // ── 大小写混合边缘 ────────────────────────────────────────────────────────

    @Test
    void mixedCaseInsert_isBlocked() {
        assertThat(validator.validate("Insert Into orders VALUES (1)").isAllowed()).isFalse();
    }

    @Test
    void selectWithEmbeddedDeleteWord_isAllowed() {
        // "deleted_at" 包含 DELETE 字符串，但作为列名不应被拦截
        SqlValidationResult result = validator.validate("SELECT deleted_at FROM orders WHERE deleted_at IS NULL");
        assertThat(result.isAllowed()).isTrue();
    }

    @Test
    void selectWithDeleteInStringLiteral_isAllowed() {
        // WHERE 子句中字符串字面量含 DML 关键词，不应被误报
        SqlValidationResult result = validator.validate(
            "SELECT * FROM orders WHERE status = 'SOFT DELETE'");
        assertThat(result.isAllowed())
            .as("String literal containing DML keyword should not be blocked")
            .isTrue();
    }

    @Test
    void selectWithUpdateInStringLiteral_isAllowed() {
        // 字符串值含 UPDATE，如 audit_action = 'UPDATE'
        SqlValidationResult result = validator.validate(
            "SELECT * FROM audit_log WHERE action = 'UPDATE'");
        assertThat(result.isAllowed())
            .as("String literal containing UPDATE should not be blocked")
            .isTrue();
    }

    // ── 列名含关键字前缀/后缀（不应误拦） ────────────────────────────────────

    @Test
    void columnNameWithExecPrefix_isAllowed() {
        // exectime / execute_flag 中含 EXEC/EXECUTE，但后面跟字母不是词边界，应放行
        SqlValidationResult result = validator.validate(
            "SELECT exectime, execute_flag FROM jobs WHERE execute_flag = 1");
        assertThat(result.isAllowed())
            .as("Column names starting with EXEC/EXECUTE should not be blocked")
            .isTrue();
    }

    @Test
    void columnNameWithDeleteSuffix_isAllowed() {
        // deleted_at 包含 DELETE 但后跟 _ 属于标识符字符，词边界正则不应匹配
        SqlValidationResult result = validator.validate(
            "SELECT id, deleted_at FROM records WHERE deleted_at IS NOT NULL");
        assertThat(result.isAllowed()).isTrue();
    }

    @Test
    void columnNameWithDropPrefix_isAllowed() {
        // dropship_count 含 DROP 前缀，不应被拦截
        SqlValidationResult result = validator.validate(
            "SELECT dropship_count FROM inventory");
        assertThat(result.isAllowed())
            .as("dropship_count column should not be blocked")
            .isTrue();
    }

    // ── 字符串字面量内 "--" 伪装成注释的堆叠查询绕过 ──────────────────────────

    @Test
    void stringLiteralContainingDashDash_doesNotMaskStackedQuery() {
        // 'x--' 是合法字符串字面量；若先剥离注释再剥离字符串，LINE_COMMENT 会从
        // 字面量内部的 -- 开始把后面真实的 "; DROP TABLE ..." 一并吞掉，导致漏判。
        // 必须先剥离字符串字面量，再剥离注释，才能正确拦截这条堆叠查询。
        SqlValidationResult result = validator.validate(
            "SELECT * FROM orders WHERE name = 'x--' ; DROP TABLE orders");
        assertThat(result.isAllowed())
            .as("Stacked query hidden after a string literal containing '--' must still be blocked")
            .isFalse();
    }

    @Test
    void stringLiteralContainingDashDash_withoutStackedQuery_isAllowed() {
        // 字符串字面量本身含 "--" 但后面没有堆叠查询时，应正常放行
        SqlValidationResult result = validator.validate(
            "SELECT * FROM orders WHERE name = 'x--'");
        assertThat(result.isAllowed())
            .as("A plain string literal containing '--' with no stacked query should be allowed")
            .isTrue();
    }
}
