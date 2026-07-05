package com.javacodeagent.core.data;

import com.javacodeagent.core.data.DataAgentConstants;
import com.javacodeagent.core.data.model.ChartSpec;
import com.javacodeagent.core.data.model.InsightResult;
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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InsightGenerator {

    private static final String INSIGHT_PROMPT = """
        You are a data analyst. Based on the SQL query results below, provide a concise business insight in Markdown format.

        Original question: {question}
        SQL executed: {sql}
        Data sample (first {sample_size} rows):
        {data_sample}

        Requirements:
        - Start with the key finding
        - Quantify with specific numbers where possible
        - Suggest one actionable recommendation
        - Keep it 2-4 sentences
        - Use {lang} language
        - Do NOT wrap in code blocks
        """;

    private final LLMClient llmClient;

    public Mono<InsightResult> generate(String question, ChartSpec chartSpec) {
        if (chartSpec.getData() == null || chartSpec.getData().isEmpty()) {
            String emptyMsg = detectLanguage(question).equals("Chinese")
                ? "查询未返回数据。" : "Query returned no data.";
            return Mono.just(new InsightResult(chartSpec, emptyMsg));
        }

        String dataSample = formatDataSample(chartSpec.getData(), DataAgentConstants.DEFAULT_INSIGHT_SAMPLE_ROWS);
        int sampleSize = Math.min(DataAgentConstants.DEFAULT_INSIGHT_SAMPLE_ROWS, chartSpec.getData().size());
        String lang = detectLanguage(question);

        String prompt = INSIGHT_PROMPT
            .replace("{question}", question)
            .replace("{sql}", chartSpec.getSql())
            .replace("{sample_size}", String.valueOf(sampleSize))
            .replace("{data_sample}", dataSample)
            .replace("{lang}", lang);

        ConversationContext ctx = ConversationContext.builder()
            .conversationId(DataAgentConstants.CONV_PREFIX_INSIGHT + UUID.randomUUID())
            .userId(DataAgentConstants.SYSTEM_USER_ID)
            .permissionLevel(PermissionLevel.READ_ONLY)
            .messages(List.of(
                Message.builder().type(MessageType.USER).content(prompt).build()
            ))
            .build();

        return llmClient.chat(ctx)
            .filter(resp -> resp != null && resp.getContent() != null && !resp.getContent().isBlank())
            .map(resp -> new InsightResult(chartSpec, resp.getContent()))
            // 若 LLM 返回空内容（filter 过滤为空 Mono），降级到 thought 字段
            .switchIfEmpty(Mono.defer(() -> {
                String fallback = chartSpec.getThought() != null && !chartSpec.getThought().isBlank()
                    ? chartSpec.getThought()
                    : (detectLanguage(question).equals("Chinese") ? "暂无洞察。" : "No insight available.");
                log.debug("InsightGenerator: LLM returned empty content, using thought as fallback");
                return Mono.just(new InsightResult(chartSpec, fallback));
            }))
            .doOnError(e -> log.error("Insight generation failed", e))
            .onErrorReturn(new InsightResult(chartSpec,
                chartSpec.getThought() != null ? chartSpec.getThought() : ""));
    }

    private String formatDataSample(List<Map<String, Object>> rows, int limit) {
        return rows.stream().limit(limit)
            .map(row -> row.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", ")))
            .collect(Collectors.joining("\n"));
    }

    private String detectLanguage(String text) {
        boolean hasChinese = text.chars().anyMatch(c -> c >= 0x4E00 && c <= 0x9FA5);
        return hasChinese ? "Chinese" : "English";
    }
}
