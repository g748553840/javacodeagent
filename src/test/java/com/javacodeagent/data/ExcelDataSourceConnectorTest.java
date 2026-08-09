package com.javacodeagent.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javacodeagent.core.data.DataAgentConstants;
import com.javacodeagent.core.data.ExcelDataSourceConnector;
import com.javacodeagent.core.data.model.DataQueryResult;
import com.javacodeagent.core.llm.LLMClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ExcelDataSourceConnector 集成测试（H2 内存数据库）。
 * 覆盖：CSV 导入、Schema 读取、NL 查询、LIMIT 防重复追加、不支持文件类型拒绝。
 */
@SpringBootTest
@TestPropertySource(properties = {
    "security.api-key=",
    "llm.api-key=test-key",
    "llm.provider=anthropic"
})
class ExcelDataSourceConnectorTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private LLMClient llmClient;

    @Autowired
    private ObjectMapper objectMapper;

    private ExcelDataSourceConnector connector;

    private final List<String> createdTables = new ArrayList<>();

    @BeforeEach
    void setUp() {
        connector = new ExcelDataSourceConnector(jdbcTemplate, llmClient, objectMapper);
    }

    @AfterEach
    void cleanUp() {
        for (String table : createdTables) {
            try {
                jdbcTemplate.execute("DROP TABLE IF EXISTS \"" + table + "\"");
            } catch (Exception ignored) {}
        }
        createdTables.clear();
    }

    // ── CSV 导入 ──────────────────────────────────────────────────────────────

    @Test
    void importCsv_createsTableAndInsertsRows() throws IOException {
        byte[] csv = "name,age,city\nAlice,30,Beijing\nBob,25,Shanghai\n"
            .getBytes(StandardCharsets.UTF_8);

        String tableName = connector.importFile(csv, "test_data.csv");
        createdTables.add(tableName);

        assertThat(tableName).startsWith("tbl_");
        // 表名长度应为 "tbl_" + TABLE_NAME_UUID_LENGTH 位十六进制
        assertThat(tableName).hasSize(4 + DataAgentConstants.TABLE_NAME_UUID_LENGTH);

        // 表应存在且有 2 行
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM \"" + tableName + "\"", Integer.class);
        assertThat(count).isEqualTo(2);
    }

    // ── getTableInfo ─────────────────────────────────────────────────────────

    @Test
    void getTableInfo_includesDdlAndSampleRows() throws IOException {
        byte[] csv = "product,price\nApple,3.5\nBanana,2.0\n"
            .getBytes(StandardCharsets.UTF_8);
        String tableName = connector.importFile(csv, "products.csv");
        createdTables.add(tableName);

        String info = connector.getTableInfo(tableName);

        assertThat(info).contains("CREATE TABLE");
        assertThat(info).containsIgnoringCase("product");
        assertThat(info).containsIgnoringCase("price");
        // 样例行注释
        assertThat(info).contains("rows from");
    }

    // ── query ────────────────────────────────────────────────────────────────

    @Test
    void query_returnsColumnsAndRows() throws IOException {
        byte[] csv = "col_a,col_b\nv1,10\nv2,20\nv3,30\n"
            .getBytes(StandardCharsets.UTF_8);
        String tableName = connector.importFile(csv, "sample.csv");
        createdTables.add(tableName);

        DataQueryResult result = connector.query("SELECT * FROM \"" + tableName + "\"", 10);

        assertThat(result.columns()).containsExactlyInAnyOrder("COL_A", "COL_B");
        assertThat(result.rows()).hasSize(3);
        assertThat(result.totalRows()).isEqualTo(3);
    }

    @Test
    void query_withLimitAlreadyInSql_doesNotDuplicateLimit() throws IOException {
        byte[] csv = "id,val\n1,a\n2,b\n3,c\n4,d\n5,e\n"
            .getBytes(StandardCharsets.UTF_8);
        String tableName = connector.importFile(csv, "limit_test.csv");
        createdTables.add(tableName);

        // SQL 中已有 LIMIT 2，connector 不应再追加一个 LIMIT
        DataQueryResult result = connector.query(
            "SELECT * FROM \"" + tableName + "\" LIMIT 2", 100);

        assertThat(result.rows()).hasSize(2);
    }

    @Test
    void query_emptyTable_returnsEmpty() throws IOException {
        byte[] csv = "x,y\n".getBytes(StandardCharsets.UTF_8);
        String tableName = connector.importFile(csv, "empty.csv");
        createdTables.add(tableName);

        DataQueryResult result = connector.query("SELECT * FROM \"" + tableName + "\"", 10);

        assertThat(result.columns()).isEmpty();
        assertThat(result.rows()).isEmpty();
    }

    // ── 不支持的文件类型 ──────────────────────────────────────────────────────

    @Test
    void importFile_unsupportedType_throwsIllegalArgument() {
        byte[] bytes = "dummy".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> connector.importFile(bytes, "data.json"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported");
    }

    // ── 列名安全化 ────────────────────────────────────────────────────────────

    @Test
    void importCsv_specialCharactersInHeader_sanitized() throws IOException {
        // 列名含空格、特殊字符，应安全化为合法列名
        byte[] csv = "First Name,Last-Name,Age#\nJohn,Doe,40\n"
            .getBytes(StandardCharsets.UTF_8);
        String tableName = connector.importFile(csv, "persons.csv");
        createdTables.add(tableName);

        String info = connector.getTableInfo(tableName);
        // 空格和特殊符号应被替换为下划线或去除
        assertThat(info).doesNotContain(" Name");
        assertThat(info).doesNotContain("Age#");
    }

    // ── 表名唯一性 ────────────────────────────────────────────────────────────

    @Test
    void importFile_twoCalls_generateDifferentTableNames() throws IOException {
        byte[] csv = "a\n1\n".getBytes(StandardCharsets.UTF_8);
        String t1 = connector.importFile(csv, "a.csv");
        String t2 = connector.importFile(csv, "b.csv");
        createdTables.add(t1);
        createdTables.add(t2);
        assertThat(t1).isNotEqualTo(t2);
    }

    // ── getTableInfo 标识符注入防护 ─────────────────────────────────────────────

    @Test
    void getTableInfo_maliciousTableName_isRejectedNotExecuted() {
        // 模拟客户端直接构造恶意 tableName 请求体（getTableInfo 之前未做格式校验，
        // getSampleRows 会把它原样拼接进 SQL 字符串，构成标识符注入）
        String malicious = "x\" UNION SELECT NULL,NULL,username,password FROM INFORMATION_SCHEMA.USERS --";

        String info = connector.getTableInfo(malicious);

        // 修复后应走 catch 分支返回错误注释，而不是把恶意片段当作真实 SQL 执行
        assertThat(info).contains("Error reading table");
        assertThat(info).doesNotContain("UNION SELECT");
    }

    @Test
    void getTableInfo_validTableName_stillWorks() throws IOException {
        byte[] csv = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);
        String tableName = connector.importFile(csv, "valid.csv");
        createdTables.add(tableName);

        String info = connector.getTableInfo(tableName);

        assertThat(info).contains("CREATE TABLE");
        assertThat(info).doesNotContain("Error reading table");
    }
}
