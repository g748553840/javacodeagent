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
    private final SqlCacheService sqlCacheService;

    public Mono<Nl2SqlResult> generateSql(String dataSourceId, String question, String schema) {
        // Cache check — identical normalized questions reuse prior SQL without an LLM call.
        // dataSourceId 必须纳入 key（见 SqlCacheService.buildKey 注释），否则不同数据源的
        // 相同问法会互相串用彼此的 SQL/schema 推理内容，构成跨数据源数据泄露。
        return sqlCacheService.get(dataSourceId, question)
            .map(cached -> {
                log.debug("SQL cache hit for question: {}", question);
                return Mono.just(cached);
            })
            .orElseGet(() -> generateSqlFromLlm(question, schema)
                .doOnSuccess(result -> sqlCacheService.put(dataSourceId, question, result)));
    }

    private Mono<Nl2SqlResult> generateSqlFromLlm(String question, String schema) {
        String systemPrompt = NL2SQL_SYSTEM_PROMPT
            .replace("{db_name}", connector.getDatabaseName())
            .replace("{dialect}", connector.getDialect())
            .replace("{table_info}", schema)
            .replace("{max_results}", String.valueOf(DataAgentConstants.DEFAULT_MAX_ROWS))
            .replace("{display_types}", DISPLAY_TYPES);

        ConversationContext ctx = ConversationContext.builder()
            .conversationId(DataAgentConstants.CONV_PREFIX_NL2SQL + UUID.randomUUID())
            .userId(DataAgentConstants.SYSTEM_USER_ID)
            .permissionLevel(PermissionLevel.READ_ONLY)
            .messages(List.of(
                Message.builder().type(MessageType.SYSTEM).content(systemPrompt).build(),
                Message.builder().type(MessageType.USER).content(question).build()
            ))
            .build();

        return llmClient.chat(ctx)
            .filter(response -> response != null && response.getContent() != null && !response.getContent().isBlank())
            .switchIfEmpty(reactor.core.publisher.Mono.error(new IllegalStateException("LLM returned empty response for NL2SQL")))
            .map(response -> {
                Nl2SqlResult result = parseNl2SqlResult(response.getContent());
                // SQL 为空时提前报错，避免后续执行空 SQL 导致 JDBC 错误信息不明确
                if (result.getSql() == null || result.getSql().isBlank()) {
                    throw new IllegalStateException("LLM generated empty SQL for question: " + question);
                }
                return result;
            })
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
        // 1. 成对去除 markdown 代码块包装（```json...``` 或 ```...```），不误删内容中的反引号
        String cleaned = text.replaceAll("(?s)```(?:json)?\\n?([\\s\\S]*?)```", "$1").trim();
        // 2. 如果清理后直接以 { 开头，让 Jackson 解析（支持任意嵌套深度）
        if (cleaned.startsWith("{")) return cleaned;
        // 3. 兜底：从第一个 '{' 到最后一个 '}' 贪婪提取（支持任意嵌套深度）。
        //    旧方法用正则 \\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\} 仅支持 2 层嵌套，
        //    thoughts 字段含 SQL 模板 {} 或 LLM 输出含深层 JSON 时解析失败。
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start != -1 && end > start) return cleaned.substring(start, end + 1);
        return "{}";
    }
}
