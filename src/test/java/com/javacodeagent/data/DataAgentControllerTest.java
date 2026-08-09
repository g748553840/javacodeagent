package com.javacodeagent.data;

import com.javacodeagent.core.data.DataAgentPipeline;
import com.javacodeagent.core.data.model.DataAnalysisReport;
import com.javacodeagent.core.data.model.DataQueryResult;
import com.javacodeagent.core.data.model.DashboardSpec;
import com.javacodeagent.core.data.model.Nl2SqlResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * DataAgentController HTTP 层测试。
 * DataAgentPipeline 整体 mock，只验证 HTTP 协议层（路径、状态码、响应体格式）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"security.api-key=", "llm.api-key=test-key", "llm.provider=anthropic"})
@AutoConfigureWebTestClient
class DataAgentControllerTest {

    @Autowired
    private WebTestClient client;

    @MockBean
    private DataAgentPipeline pipeline;

    // ── POST /api/v1/data-agent/query ─────────────────────────────────────────

    @Test
    void query_returnsOkWithReport() {
        DataAnalysisReport report = DataAnalysisReport.builder()
            .question("test q")
            .insightMarkdown("some insight")
            .success(true)
            .build();
        when(pipeline.analyze(any())).thenReturn(Mono.just(report));

        client.post().uri("/api/v1/data-agent/query")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"question\":\"test q\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.success").isEqualTo(true)
            .jsonPath("$.question").isEqualTo("test q")
            .jsonPath("$.insightMarkdown").isEqualTo("some insight");
    }

    // ── POST /api/v1/data-agent/nl2sql ────────────────────────────────────────

    @Test
    void nl2sql_returnsSqlResult() {
        Nl2SqlResult nl2sql = Nl2SqlResult.builder()
            .sql("SELECT * FROM orders")
            .displayType("response_table")
            .thought("direct query")
            .build();
        when(pipeline.generateSqlOnly(any())).thenReturn(Mono.just(nl2sql));

        client.post().uri("/api/v1/data-agent/nl2sql")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"question\":\"show all orders\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.sql").isEqualTo("SELECT * FROM orders")
            .jsonPath("$.displayType").isEqualTo("response_table");
    }

    // ── POST /api/v1/data-agent/execute ──────────────────────────────────────

    @Test
    void execute_validSql_returnsQueryResult() {
        DataQueryResult qr = new DataQueryResult(
            List.of("id", "name"),
            List.of(List.of(1, "Alice")),
            1,
            "SELECT id, name FROM users"
        );
        when(pipeline.executeSql(any())).thenReturn(Mono.just(qr));

        client.post().uri("/api/v1/data-agent/execute")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"sql\":\"SELECT id, name FROM users\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.totalRows").isEqualTo(1)
            .jsonPath("$.columns[0]").isEqualTo("id");
    }

    @Test
    void execute_missingsql_returnsBadRequest() {
        client.post().uri("/api/v1/data-agent/execute")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{}")
            .exchange()
            .expectStatus().isBadRequest();
    }

    // ── GET /api/v1/data-agent/schema ─────────────────────────────────────────

    @Test
    void schema_returnsDataSourceInfo() {
        Map<String, Object> schemaInfo = Map.of(
            "database", "PUBLIC",
            "dialect", "h2",
            "tables", List.of("ORDERS", "USERS"),
            "tableCount", 2
        );
        when(pipeline.getSchema()).thenReturn(Mono.just(schemaInfo));

        client.get().uri("/api/v1/data-agent/schema")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.dialect").isEqualTo("h2")
            .jsonPath("$.tableCount").isEqualTo(2);
    }

    // ── POST /api/v1/data-agent/dashboard ────────────────────────────────────

    @Test
    void dashboard_returnsDashboardSpec() {
        DashboardSpec spec = DashboardSpec.builder()
            .title("Sales Dashboard")
            .charts(List.of())
            .build();
        when(pipeline.generateDashboard(any())).thenReturn(Mono.just(spec));

        client.post().uri("/api/v1/data-agent/dashboard")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"question\":\"sales overview\"}")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.title").isEqualTo("Sales Dashboard");
    }

    // ── POST /api/v1/data-agent/query/stream SSE ─────────────────────────────

    @Test
    void queryStream_emitsSseEvents() {
        when(pipeline.analyzeStream(any())).thenReturn(Flux.just(
            "{\"type\":\"started\"}",
            "{\"type\":\"sql_generated\",\"sql\":\"SELECT 1\"}",
            "{\"type\":\"done\"}"
        ));

        client.post().uri("/api/v1/data-agent/query/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"question\":\"test\"}")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM);
    }
}
