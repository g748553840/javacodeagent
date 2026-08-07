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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
        return generate(question, connector, "default");
    }

    /**
     * 为指定数据源生成多图 Dashboard。
     *
     * @param question     自然语言问题
     * @param targetConnector 目标数据源连接器（支持多数据源路由）
     * @param dataSourceId    数据源标识（用于 Schema 向量索引）
     */
    public Mono<DashboardSpec> generate(String question, DataSourceConnector targetConnector,
                                        String dataSourceId) {
        return schemaRetriever.retrieve(question, targetConnector, dataSourceId)
            .flatMap(schema -> {
                String prompt = DASHBOARD_PROMPT
                    .replace("{db_name}", targetConnector.getDatabaseName())
                    .replace("{dialect}", targetConnector.getDialect())
                    .replace("{schema}", schema)
                    .replace("{question}", question);

                ConversationContext ctx = ConversationContext.builder()
                    .conversationId(DataAgentConstants.CONV_PREFIX_DASHBOARD + UUID.randomUUID())
                    .userId(DataAgentConstants.SYSTEM_USER_ID)
                    .permissionLevel(PermissionLevel.READ_ONLY)
                    .messages(List.of(
                        Message.builder().type(MessageType.USER).content(prompt).build()
                    ))
                    .build();

                return llmClient.chat(ctx);
            })
            .flatMap(resp -> executeDashboardSqls(resp.getContent(), question, targetConnector));
    }

    private Mono<DashboardSpec> executeDashboardSqls(String llmOutput, String question,
                                                      DataSourceConnector targetConnector) {
        List<JsonNode> chartNodes = new ArrayList<>();
        // 记录解析失败原因：若为 null 且最终 charts 为空，说明 LLM 本身就没生成任何图表定义
        // （不太可能但理论存在）；若非 null，则必须让调用方知道这是解析失败而非"正常无图表"
        String parseError = null;
        try {
            String jsonArray = extractJsonArray(llmOutput);
            JsonNode charts = objectMapper.readTree(jsonArray);
            charts.forEach(chartNodes::add);
        } catch (Exception e) {
            log.error("Failed to parse dashboard LLM output: {}", llmOutput, e);
            parseError = "Failed to parse LLM dashboard output: " + e.getMessage();
        }

        final String finalParseError = parseError;
        return Flux.fromIterable(chartNodes)
            .flatMap(chart -> {
                String sql = chart.path("sql").asText();
                String displayType = chart.path("display_type").asText(DataAgentConstants.DEFAULT_DISPLAY_TYPE);
                String title = chart.path("title").asText();
                String thought = chart.path("thought").asText();

                SqlValidationResult valid = sqlValidator.validate(sql);
                if (!valid.isAllowed()) {
                    return Mono.just(ChartSpec.builder()
                        .sql(sql).displayType(displayType).title(title).thought(thought)
                        .errMsg(valid.reason()).build());
                }
                return Mono.fromCallable(() ->
                    targetConnector.executeQuery(sql, DataAgentConstants.DEFAULT_MAX_ROWS, DataAgentConstants.DEFAULT_QUERY_TIMEOUT)
                ).subscribeOn(Schedulers.boundedElastic())
                .map(result -> ChartSpec.builder()
                    .sql(sql).displayType(displayType).title(title).thought(thought)
                    .data(toRecords(result)).build())
                .onErrorResume(e -> {
                    log.warn("Dashboard chart SQL failed: {} - {}", sql, e.getMessage());
                    return Mono.just(ChartSpec.builder()
                        .sql(sql).displayType(displayType).title(title).thought(thought)
                        .errMsg(e.getMessage()).build());
                });
            }, DataAgentConstants.DASHBOARD_CHART_CONCURRENCY)
            .collectList()
            .map(chartSpecs -> DashboardSpec.builder()
                .title(question + DataAgentConstants.DASHBOARD_TITLE_SUFFIX)
                .charts(chartSpecs)
                // chartSpecs 为空且存在解析错误时显式标注，避免前端把它当成
                // "LLM 判断该问题不需要图表" 的正常空结果
                .errMsg(chartSpecs.isEmpty() ? finalParseError : null)
                .build());
    }

    private String extractJsonArray(String text) {
        if (text == null) return "[]";
        // 先去除 markdown 代码块包装（成对匹配，不误删内容中的反引号）
        String cleaned = text.replaceAll("(?s)```(?:json)?\\n?([\\s\\S]*?)```", "$1").trim();
        // 贪婪匹配最外层 JSON 数组（从第一个 '[' 到最后一个 ']'），
        // 确保包含内部嵌套数组（如字段值为数组时不会提前截断）
        Pattern p = Pattern.compile("\\[[\\s\\S]*]");
        Matcher m = p.matcher(cleaned);
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
