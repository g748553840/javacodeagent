package com.javacodeagent.core.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javacodeagent.core.data.model.Nl2SqlResult;
import com.javacodeagent.core.enums.MessageType;
import com.javacodeagent.core.enums.PermissionLevel;
import com.javacodeagent.core.llm.LLMClient;
import com.javacodeagent.core.model.ConversationContext;
import com.javacodeagent.core.model.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class Nl2SqlService {

    private static final String NL2SQL_SYSTEM_PROMPT = """
        You are a database expert. Answer user questions with SQL queries based on the database structure provided.
        Database: {db_name}
        Dialect: {dialect}
        Schema:
        {table_info}

        Constraints:
        1. Generate syntactically correct {dialect} SQL based on user intent
        2. Limit results to at most {max_results} rows unless user specifies otherwise
        3. Only use tables from the schema above
        4. Check SQL correctness and optimize performance
        5. Choose the best chart type from: {display_types}

        Respond ONLY as valid JSON (no markdown, no explanation outside the JSON):
        {"thoughts": "your reasoning", "sql": "SELECT ...", "display_type": "response_table"}
        """;

    private static final String DISPLAY_TYPES = """
        response_line_chart: trend comparison over time
        response_pie_chart: proportion or distribution statistics
        response_table: many columns or non-numeric data
        response_bar_chart: category comparison
        response_scatter_chart: variable relationships or outlier detection
        response_area_chart: time series with filled area
        response_heatmap: large datasets or time-series distribution""";

    private final LLMClient llmClient;
    private final DataSourceConnector connector;
    private final ObjectMapper objectMapper;

    public Mono<Nl2SqlResult> generateSql(String question, String schema) {
        String systemPrompt = NL2SQL_SYSTEM_PROMPT
            .replace("{db_name}", connector.getDatabaseName())
            .replace("{dialect}", connector.getDialect())
            .replace("{table_info}", schema)
            .replace("{max_results}", "200")
            .replace("{display_types}", DISPLAY_TYPES);

        ConversationContext ctx = ConversationContext.builder()
            .conversationId("nl2sql-" + UUID.randomUUID())
            .userId("system")
            .permissionLevel(PermissionLevel.READ_ONLY)
            .messages(List.of(
                Message.builder().type(MessageType.SYSTEM).content(systemPrompt).build(),
                Message.builder().type(MessageType.USER).content(question).build()
            ))
            .build();

        return llmClient.chat(ctx)
            .map(response -> parseNl2SqlResult(response.getContent()))
            .doOnSuccess(r -> log.debug("NL2SQL generated: displayType={} sql={}", r.getDisplayType(), r.getSql()))
            .doOnError(e -> log.error("NL2SQL failed for question: {}", question, e));
    }

    private Nl2SqlResult parseNl2SqlResult(String content) {
        try {
            String json = extractJson(content);
            JsonNode node = objectMapper.readTree(json);
            return Nl2SqlResult.builder()
                .thought(node.path("thoughts").asText(""))
                .sql(node.path("sql").asText(""))
                .displayType(node.path("display_type").asText("response_table"))
                .build();
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse NL2SQL JSON output, raw: {}", content);
            throw new RuntimeException("Failed to parse LLM SQL output: " + e.getMessage(), e);
        }
    }

    private String extractJson(String text) {
        if (text == null) return "{}";
        // Try to extract first JSON object from text
        Pattern p = Pattern.compile("\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}", Pattern.DOTALL);
        Matcher m = p.matcher(text);
        if (m.find()) return m.group();
        return text.trim();
    }
}
