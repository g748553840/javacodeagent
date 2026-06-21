package com.javacodeagent.core.data.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javacodeagent.core.agent.Agent;
import com.javacodeagent.core.agent.AgentContext;
import com.javacodeagent.core.agent.AgentResult;
import com.javacodeagent.core.agent.AgentTask;
import com.javacodeagent.core.data.DataAgentConstants;
import com.javacodeagent.core.enums.MessageType;
import com.javacodeagent.core.enums.PermissionLevel;
import com.javacodeagent.core.llm.LLMClient;
import com.javacodeagent.core.model.ConversationContext;
import com.javacodeagent.core.model.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 异常检测 Agent — 分析查询结果，识别统计异常值、缺失值和分布异常。
 *
 * <p>输入参数（via {@code AgentTask.parameters}）：
 * <ul>
 *   <li>{@code question} — 原始自然语言问题</li>
 *   <li>{@code dataSample} — 查询数据的字符串摘要（最多 50 行）</li>
 * </ul>
 * 输出：异常描述列表，序列化为 JSON 数组字符串，由 {@code AgentResult.output} 返回。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnomalyDetectorAgent implements Agent {

    private static final Pattern JSON_ARRAY = Pattern.compile("\\[.*?\\]", Pattern.DOTALL);

    private final LLMClient llmClient;
    private final ObjectMapper objectMapper;

    @Override
    public String getType() { return "anomaly-detector"; }

    @Override
    public String getDescription() { return "Detects statistical anomalies and outliers in query result data"; }

    @Override
    public List<String> getAvailableTools() { return List.of(); }

    @Override
    public AgentResult process(AgentTask task, AgentContext context) {
        String question = (String) task.getParameters().getOrDefault("question", "");
        String dataSample = (String) task.getParameters().getOrDefault("dataSample", "（无数据）");

        String prompt = """
            You are a data analyst specializing in anomaly detection.
            Analyze the query result below for statistical anomalies, outliers, and unusual patterns.

            User question: %s

            Data sample:
            %s

            Identify:
            1. Numeric outliers (values significantly above/below average, e.g. ≥3σ)
            2. Suspicious zero or null values in important columns
            3. Unexpected distribution gaps or spikes
            4. Duplicates or inconsistencies

            Respond ONLY with a JSON array of short anomaly descriptions (empty array if none found):
            ["column 'amount' has outlier 99999 — 5× above mean", "null values in 'region' column (3 rows)"]
            """.formatted(question, dataSample);

        ConversationContext ctx = ConversationContext.builder()
            .conversationId(DataAgentConstants.CONV_PREFIX_NL2SQL + "anomaly-" + UUID.randomUUID())
            .userId(DataAgentConstants.SYSTEM_USER_ID)
            .permissionLevel(PermissionLevel.READ_ONLY)
            .messages(List.of(
                Message.builder().type(MessageType.USER).content(prompt).build()
            ))
            .build();

        try {
            String raw = llmClient.chat(ctx).block().getContent();
            List<String> anomalies = parseJsonArray(raw);
            log.debug("AnomalyDetector found {} anomalies", anomalies.size());
            return AgentResult.builder()
                .success(true)
                .output(objectMapper.writeValueAsString(anomalies))
                .metadata(Map.of("anomalyCount", anomalies.size()))
                .build();
        } catch (Exception e) {
            log.warn("AnomalyDetector failed: {}", e.getMessage());
            return AgentResult.builder().success(false).error(e.getMessage()).build();
        }
    }

    private List<String> parseJsonArray(String text) {
        if (text == null) return List.of();
        Matcher m = JSON_ARRAY.matcher(text);
        if (m.find()) {
            try {
                return objectMapper.readValue(m.group(), new TypeReference<List<String>>() {});
            } catch (Exception e) {
                log.debug("Could not parse anomaly JSON array, returning empty");
            }
        }
        return new ArrayList<>();
    }
}
