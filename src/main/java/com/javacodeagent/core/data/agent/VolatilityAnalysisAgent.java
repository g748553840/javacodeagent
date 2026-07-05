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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 波动性分析 Agent — 分析数值列的均值、方差、趋势方向和极值波动。
 *
 * <p>输入参数（via {@code AgentTask.parameters}）：
 * <ul>
 *   <li>{@code question} — 原始自然语言问题</li>
 *   <li>{@code dataSample} — 查询数据摘要</li>
 * </ul>
 * 输出：波动指标 JSON 对象，由 {@code AgentResult.output} 返回。
 * 示例：{@code {"cv": 0.42, "trend": "increasing", "peakValue": 99999, "troughValue": 10}}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VolatilityAnalysisAgent implements Agent {

    // 波动分析 Agent 不再需要 Pattern 字段：改用 indexOf/lastIndexOf 方式提取 JSON 对象，
    // 避免非贪婪 .*? 在嵌套对象（如 "metadata":{...} 子键）处提前截断

    private final LLMClient llmClient;
    private final ObjectMapper objectMapper;

    @Override
    public String getType() { return "volatility-analysis"; }

    @Override
    public String getDescription() { return "Measures variance, trend direction and volatility metrics in time-series or numeric data"; }

    @Override
    public List<String> getAvailableTools() { return List.of(); }

    @Override
    public AgentResult process(AgentTask task, AgentContext context) {
        String question = (String) task.getParameters().getOrDefault("question", "");
        String dataSample = (String) task.getParameters().getOrDefault("dataSample", "（无数据）");

        String prompt = """
            You are a quantitative analyst specializing in time-series and distribution analysis.
            Analyze the data below for volatility and trend characteristics.

            User question: %s

            Data sample:
            %s

            Compute or estimate:
            1. cv (coefficient of variation = std_dev / mean for primary numeric column, 0–1+ scale)
            2. trend: "increasing" | "decreasing" | "stable" | "volatile" | "insufficient_data"
            3. peakValue: maximum observed value (null if not applicable)
            4. troughValue: minimum observed value (null if not applicable)
            5. observations: array of 1–3 key volatility insights

            Respond ONLY with a JSON object (no markdown, no extra text):
            {"cv": 0.35, "trend": "increasing", "peakValue": 99999, "troughValue": 100, "observations": ["Revenue peaked in Q3", "Steady month-over-month growth"]}
            """.formatted(question, dataSample);

        ConversationContext ctx = ConversationContext.builder()
            .conversationId(DataAgentConstants.CONV_PREFIX_VOLATILITY + UUID.randomUUID())
            .userId(DataAgentConstants.SYSTEM_USER_ID)
            .permissionLevel(PermissionLevel.READ_ONLY)
            .messages(List.of(
                Message.builder().type(MessageType.USER).content(prompt).build()
            ))
            .build();

        try {
            var response = llmClient.chat(ctx).block();
            String raw = (response != null) ? response.getContent() : null;
            Map<String, Object> metrics = parseJsonObject(raw);
            log.debug("VolatilityAnalysis: trend={} cv={}", metrics.get("trend"), metrics.get("cv"));
            return AgentResult.builder()
                .success(true)
                .output(objectMapper.writeValueAsString(metrics))
                .metadata(metrics)
                .build();
        } catch (Exception e) {
            log.warn("VolatilityAnalysis failed: {}", e.getMessage());
            return AgentResult.builder().success(false).error(e.getMessage()).build();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String text) {
        if (text == null) return new HashMap<>();
        // 从第一个 '{' 到最后一个 '}' 贪婪提取，支持任意嵌套深度
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start != -1 && end > start) {
            try {
                return objectMapper.readValue(text.substring(start, end + 1), new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.debug("Could not parse volatility JSON: {}", e.getMessage());
            }
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("trend", "insufficient_data");
        return fallback;
    }
}
