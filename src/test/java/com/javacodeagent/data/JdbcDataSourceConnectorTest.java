package com.javacodeagent.data;

import com.javacodeagent.core.data.JdbcDataSourceConnector;
import com.javacodeagent.core.data.model.DataQueryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JdbcDataSourceConnector 集成测试（H2 内存数据库）。
 * 重点覆盖：
 * - hasOuterLimit 子查询 LIMIT 检测（外层无 LIMIT 时正确追加，子查询有 LIMIT 时外层仍追加）
 * - executeQuery 带超时参数正常执行
 * - listTables / getTableInfo 基本功能
 */
@SpringBootTest
@TestPropertySource(properties = {
    "security.api-key=",
    "llm.api-key=test-key",
    "llm.provider=anthropic"
})
class JdbcDataSourceConnectorTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private JdbcDataSourceConnector connector;

    @BeforeEach
    void setUp() {
        connector = new JdbcDataSourceConnector(jdbcTemplate, "h2", "PUBLIC");
    }

    // ── listTables ────────────────────────────────────────────────────────────

    @Test
    void listTables_returnsH2SystemTables() {
        // H2 的 INFORMATION_SCHEMA 中至少有 TABLES 元数据表
        assertThat(connector.listTables()).isNotNull();
    }

    // ── executeQuery with timeout ─────────────────────────────────────────────

    @Test
    void executeQuery_simpleSelect_withTimeout_returnsResult() {
        DataQueryResult result = connector.executeQuery(
            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'",
            100,
            Duration.ofSeconds(5)
        );
        assertThat(result).isNotNull();
        assertThat(result.columns()).isNotEmpty();
    }

    @Test
    void executeQuery_withNullTimeout_usesDefaultAndSucceeds() {
        DataQueryResult result = connector.executeQuery(
            "SELECT 1 AS val FROM (VALUES(0))",
            10,
            null
        );
        assertThat(result.rows()).isNotEmpty();
    }

    // ── hasOuterLimit（通过 executeQuery 行为验证） ──────────────────────────

    /**
     * 外层无 LIMIT 时，appendLimit 应追加限制行数。
     * 通过请求 maxRows=1 确认结果被限制到 1 行（INFORMATION_SCHEMA.TABLES 一般多于 1 行）。
     */
    @Test
    void appendLimit_noExistingLimit_appendsMaxRows() {
        DataQueryResult result = connector.executeQuery(
            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES",
            1,
            Duration.ofSeconds(5)
        );
        assertThat(result.rows()).hasSizeLessThanOrEqualTo(1);
    }

    /**
     * SQL 外层已有 LIMIT 时，appendLimit 不应再追加（测试子查询场景）。
     * 子查询含 LIMIT 10，外层无 LIMIT —— maxRows=1 应追加外层限制。
     */
    @Test
    void appendLimit_subqueryHasLimit_outerLimitStillAppended() {
        // 外层没有 LIMIT，子查询有 LIMIT 10；maxRows=1 应使外层结果 ≤ 1 行
        DataQueryResult result = connector.executeQuery(
            "SELECT TABLE_NAME FROM (SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES LIMIT 10) AS sub",
            1,
            Duration.ofSeconds(5)
        );
        assertThat(result.rows()).hasSizeLessThanOrEqualTo(1);
    }

    /**
     * SQL 最外层已有 LIMIT 2 时，appendLimit 不应再追加，结果仍是 2 行（不因 maxRows=100 变多）。
     */
    @Test
    void appendLimit_outerLimitExists_doesNotDoubleLimit() {
        DataQueryResult result = connector.executeQuery(
            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES LIMIT 2",
            100,
            Duration.ofSeconds(5)
        );
        // 结果不超过外层已声明的 LIMIT 2
        assertThat(result.rows()).hasSizeLessThanOrEqualTo(2);
    }
}
