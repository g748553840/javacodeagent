package com.javacodeagent.core.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javacodeagent.core.data.model.ChartSpec;
import com.javacodeagent.core.data.model.DashboardSpec;
import com.javacodeagent.core.data.model.DataQueryResult;
import com.javacodeagent.core.data.model.SqlValidationResult;
import com.javacodeagent.core.enums.MessageType;
import com.javacodeagent.core.enums.PermissionLevel;
import com.javacodeagent.core.llm.LLMClient;
import com.javacodeagent.core.model.ConversationContext;
import com.javacodeagent.core.model.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardGenerator {

    private static final String DASHBOARD_PROMPT = """
        You are a data analyst. Generate a multi-chart dashboard for the following question.
        Database: {db_name}
        Dialect: {dialect}
        Schema:
        {schema}

        Question: {question}

        Generate 2-4 complementary charts covering different analytical angles.
        Respond ONLY as a JSON array (no markdown):
        [
          {"sql": "SELECT ...", "display_type": "response_bar_chart", "title": "...", "thought": "..."},
          {"sql": "SELECT ...", "display_type": "response_line_chart", "title": "...", "thought": "..."}
        ]
        Each sql must be a valid {dialect} SELECT statement. No DML allowed.
        """;

    private final LLMClient llmClient;
    private final SchemaRetriever schemaRetriever;
    private final SqlValidator sqlValidator;
    private final DataSourceConnector connector;
    private final ObjectMapper objectMapper;

    public Mono<DashboardSpec> generate(String question) {
        return schemaRetriever.retrieve(question)
            .flatMap(schema -> {
                String prompt = DASHBOARD_PROMPT
                    .replace("{db_name}", connector.getDatabaseName())
                    .replace("{dialect}", connector.getDialect())
                    .replace("{schema}", schema)
                    .replace("{question}", question);

                ConversationContext ctx = ConversationContext.builder()
                    .conversationId("dashboard-" + UUID.randomUUID())
                    .userId("system")
                    .permissionLevel(PermissionLevel.READ_ONLY)
                    .messages(List.of(
                        Message.builder().type(MessageType.USER).content(prompt).build()
                    ))
                    .build();

                return llmClient.chat(ctx);
            })
            .flatMap(resp -> executeDashboardSqls(resp.getContent(), question));
    }

    private Mono<DashboardSpec> executeDashboardSqls(String llmOutput, String question) {
        return Mono.fromCallable(() -> {
            List<ChartSpec> chartSpecs = new ArrayList<>();
            try {
                String jsonArray = extractJsonArray(llmOutput);
                JsonNode charts = objectMapper.readTree(jsonArray);
                for (JsonNode chart : charts) {
                    String sql = chart.path("sql").asText();
                    String displayType = chart.path("display_type").asText("response_table");
                    String title = chart.path("title").asText();
                    String thought = chart.path("thought").asText();

                    SqlValidationResult valid = sqlValidator.validate(sql);
                    if (!valid.isAllowed()) {
                        chartSpecs.add(ChartSpec.builder()
                            .sql(sql).displayType(displayType).title(title).thought(thought)
                            .errMsg(valid.reason()).build());
                        continue;
                    }
                    try {
                        DataQueryResult result = connector.executeQuery(sql, 200, Duration.ofSeconds(30));
                        chartSpecs.add(ChartSpec.builder()
                            .sql(sql).displayType(displayType).title(title).thought(thought)
                            .data(toRecords(result)).build());
                    } catch (Exception e) {
                        log.warn("Dashboard chart SQL failed: {} - {}", sql, e.getMessage());
                        chartSpecs.add(ChartSpec.builder()
                            .sql(sql).displayType(displayType).title(title).thought(thought)
                            .errMsg(e.getMessage()).build());
                    }
                }
            } catch (Exception e) {
                log.error("Failed to parse dashboard LLM output: {}", llmOutput, e);
            }
            return new DashboardSpec(question + " 综合看板", chartSpecs, "default");
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String extractJsonArray(String text) {
        if (text == null) return "[]";
        Pattern p = Pattern.compile("\\[.*\\]", Pattern.DOTALL);
        Matcher m = p.matcher(text);
        return m.find() ? m.group() : "[]";
    }

    private List<Map<String, Object>> toRecords(DataQueryResult qr) {
        if (qr.columns().isEmpty()) return List.of();
        return qr.rows().stream()
            .map(row -> {
                Map<String, Object> record = new LinkedHashMap<>();
                for (int i = 0; i < qr.columns().size(); i++) {
                    record.put(qr.columns().get(i), i < row.size() ? row.get(i) : null);
                }
                return record;
            })
            .collect(Collectors.toList());
    }
}
