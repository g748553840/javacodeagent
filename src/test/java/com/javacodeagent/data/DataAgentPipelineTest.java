package com.javacodeagent.data;

import com.javacodeagent.core.data.DataAgentPipeline;
import com.javacodeagent.core.data.model.DataAnalysisReport;
import com.javacodeagent.core.data.model.DataQueryRequest;
import com.javacodeagent.core.data.model.DataQueryResult;
import com.javacodeagent.core.data.model.DashboardSpec;
import com.javacodeagent.core.data.model.Nl2SqlResult;
import com.javacodeagent.core.llm.LLMClient;
import com.javacodeagent.core.model.LLMResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
/**
 * DataAgentPipeline 集成测试。
 * LLMClient mock 掉，其余组件（SchemaRetriever、SqlValidator、SqlExecutor、
 * InsightGenerator、DashboardGenerator）全量走真实逻辑（H2 内存库）。
 *
 * 覆盖场景：
 * - executeSql 合法 SELECT
 * - executeSql 非法 DML 被拒绝
 * - generateSqlOnly 返回 Nl2SqlResult
 * - getSchema 返回数据源元信息
 * - analyzeStream 推送正确的 SSE 事件序列
 * - generateDashboard 返回 DashboardSpec（多图）
 */
@SpringBootTest
@TestPropertySource(properties = {
    "security.api-key=",
    "llm.api-key=test-key",
    "llm.provider=anthropic"
})
class DataAgentPipelineTest {

    @Autowired
    private DataAgentPipeline pipeline;

    @MockBean
    private LLMClient llmClient;

    // ── executeSql ────────────────────────────────────────────────────────────

    @Test
    void executeSql_validSelect_returnsResult() {
        Mono<DataQueryResult> result = pipeline.executeSql(
            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'");

        StepVerifier.create(result)
            .assertNext(r -> {
                assertThat(r.columns()).isNotEmpty();
                assertThat(r.totalRows()).isGreaterThanOrEqualTo(0);
            })
            .verifyComplete();
    }

    @Test
    void executeSql_dmlStatement_isRejected() {
        Mono<DataQueryResult> result = pipeline.executeSql("DROP TABLE information_schema.tables");

        StepVerifier.create(result)
            .expectErrorSatisfies(err ->
                assertThat(err).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SQL rejected"))
            .verify();
    }

    @Test
    void executeSql_insertStatement_isRejected() {
        Mono<DataQueryResult> result = pipeline.executeSql("INSERT INTO foo VALUES (1)");

        StepVerifier.create(result)
            .expectErrorSatisfies(err ->
                assertThat(err).isInstanceOf(IllegalArgumentException.class))
            .verify();
    }

    // ── getSchema ─────────────────────────────────────────────────────────────

    @Test
    void getSchema_returnsDatabaseInfo() {
        StepVerifier.create(pipeline.getSchema())
            .assertNext(schema -> {
                assertThat(schema).containsKey("database");
                assertThat(schema).containsKey("dialect");
                assertThat(schema).containsKey("tables");
                assertThat(schema).containsKey("tableCount");
                assertThat(schema.get("dialect")).isEqualTo("h2");
            })
            .verifyComplete();
    }

    // ── generateSqlOnly ───────────────────────────────────────────────────────

    @Test
    void generateSqlOnly_mockedLlm_returnsNl2SqlResult() {
        // LLM 返回合法的 JSON 格式响应
        String llmJson = "{\"thoughts\":\"count all tables\","
            + "\"sql\":\"SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES\","
            + "\"display_type\":\"response_table\"}";
        when(llmClient.chat(any()))
            .thenReturn(Mono.just(LLMResponse.builder()
                .content(llmJson)
                .stopReason("end_turn")
                .build()));

        DataQueryRequest request = DataQueryRequest.builder()
            .question("how many tables are there?")
            .build();

        StepVerifier.create(pipeline.generateSqlOnly(request))
            .assertNext(result -> {
                assertThat(result.getSql()).contains("SELECT");
                assertThat(result.getDisplayType()).isEqualTo("response_table");
                assertThat(result.getThought()).isNotBlank();
            })
            .verifyComplete();
    }

    // ── analyzeStream SSE 事件序列 ────────────────────────────────────────────

    @Test
    void analyzeStream_publishesExpectedEventTypes() {
        String nl2SqlJson = "{\"thoughts\":\"count rows\","
            + "\"sql\":\"SELECT COUNT(*) AS total FROM INFORMATION_SCHEMA.TABLES\","
            + "\"display_type\":\"response_table\"}";
        String insightText = "There are some tables in the database.";

        when(llmClient.chat(any()))
            .thenReturn(Mono.just(LLMResponse.builder()
                .content(nl2SqlJson)
                .stopReason("end_turn")
                .build()))
            .thenReturn(Mono.just(LLMResponse.builder()
                .content(insightText)
                .stopReason("end_turn")
                .build()));

        DataQueryRequest request = DataQueryRequest.builder()
            .question("how many tables exist?")
            .build();

        // 使用 StepVerifier 替代 .block()，避免潜在的响应式死锁风险
        StepVerifier.create(pipeline.analyzeStream(request).collectList())
            .assertNext(events -> {
                assertThat(events).isNotNull();
                // 必须包含 started、schema_retrieved、sql_generated、sql_executed、insight_ready、done
                assertThat(events.stream().anyMatch(e -> e.contains("\"started\""))).isTrue();
                assertThat(events.stream().anyMatch(e -> e.contains("\"schema_retrieved\""))).isTrue();
                assertThat(events.stream().anyMatch(e -> e.contains("\"sql_generated\""))).isTrue();
                assertThat(events.stream().anyMatch(e -> e.contains("\"sql_executed\""))).isTrue();
                assertThat(events.stream().anyMatch(e -> e.contains("\"insight_ready\""))).isTrue();
                assertThat(events.stream().anyMatch(e -> e.contains("\"done\""))).isTrue();
            })
            .verifyComplete();
    }

    // ── generateDashboard ─────────────────────────────────────────────────────

    @Test
    void generateDashboard_returnsDashboardSpec() {
        String dashboardJson = "["
            + "{\"sql\":\"SELECT COUNT(*) AS cnt FROM INFORMATION_SCHEMA.TABLES\","
            + "\"display_type\":\"response_table\",\"title\":\"Table Count\",\"thought\":\"counts\"},"
            + "{\"sql\":\"SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES LIMIT 5\","
            + "\"display_type\":\"response_table\",\"title\":\"Table List\",\"thought\":\"list\"}"
            + "]";
        when(llmClient.chat(any()))
            .thenReturn(Mono.just(LLMResponse.builder()
                .content(dashboardJson)
                .stopReason("end_turn")
                .build()));

        DataQueryRequest request = DataQueryRequest.builder()
            .question("show database overview")
            .build();

        StepVerifier.create(pipeline.generateDashboard(request))
            .assertNext(spec -> {
                assertThat(spec.getTitle()).isNotBlank();
                assertThat(spec.getCharts()).hasSize(2);
                assertThat(spec.getCharts().get(0).getDisplayType()).isEqualTo("response_table");
            })
            .verifyComplete();
    }

    // ── analyze 完整流水线 ────────────────────────────────────────────────────

    @Test
    void analyze_withMockedLlm_returnsReport() {
        String nl2SqlJson = "{\"thoughts\":\"count tables\","
            + "\"sql\":\"SELECT COUNT(*) AS total FROM INFORMATION_SCHEMA.TABLES\","
            + "\"display_type\":\"response_table\"}";
        String insightText = "The database contains tables.";

        when(llmClient.chat(any()))
            .thenReturn(Mono.just(LLMResponse.builder().content(nl2SqlJson).stopReason("end_turn").build()))
            .thenReturn(Mono.just(LLMResponse.builder().content(insightText).stopReason("end_turn").build()));

        DataQueryRequest request = DataQueryRequest.builder()
            .question("how many tables exist?")
            .build();

        StepVerifier.create(pipeline.analyze(request))
            .assertNext((DataAnalysisReport report) -> {
                assertThat(report.isSuccess()).isTrue();
                assertThat(report.getQuestion()).isEqualTo("how many tables exist?");
                assertThat(report.getChartSpec()).isNotNull();
                assertThat(report.getInsightMarkdown()).isNotBlank();
            })
            .verifyComplete();
    }
}
