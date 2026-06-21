package com.javacodeagent.core.data.agent;

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

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 报告生成 Agent — 综合数据摘要、异常检测结论、波动性分析指标，生成 Markdown 综合报告。
 *
 * <p>输入参数（via {@code AgentTask.parameters}）：
 * <ul>
 *   <li>{@code question} — 原始问题</li>
 *   <li>{@code dataSummary} — 数据摘要（行数、列名、示例数据）</li>
 *   <li>{@code anomalies} — 异常列表（JSON 字符串）</li>
 *   <li>{@code volatility} — 波动指标（JSON 字符串）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportGenerationAgent implements Agent {

    private final LLMClient llmClient;

    @Override
    public String getType() { return "report-generation"; }

    @Override
    public String getDescription() { return "Compiles anomaly and volatility findings into a comprehensive Markdown analysis report"; }

    @Override
    public List<String> getAvailableTools() { return List.of(); }

    @Override
    public AgentResult process(AgentTask task, AgentContext context) {
        String question     = (String) task.getParameters().getOrDefault("question", "");
        String dataSummary  = (String) task.getParameters().getOrDefault("dataSummary", "");
        String anomalies    = (String) task.getParameters().getOrDefault("anomalies", "[]");
        String volatility   = (String) task.getParameters().getOrDefault("volatility", "{}");

        String prompt = """
            You are a senior data analyst. Compile a comprehensive executive report based on the multi-agent analysis below.

            ## User Question
            %s

            ## Data Summary
            %s

            ## Anomaly Detection Findings
            %s

            ## Volatility & Trend Analysis
            %s

            Write a professional Markdown report with these sections:
            1. **Executive Summary** (2–3 sentences)
            2. **Key Findings** (bullet points)
            3. **Anomalies & Risks** (list anomalies with potential impact; omit section if none found)
            4. **Trend & Volatility** (based on volatility metrics)
            5. **Recommendations** (2–3 actionable items)

            Use Markdown formatting. Be concise and data-driven.
            """.formatted(question, dataSummary, anomalies, volatility);

        ConversationContext ctx = ConversationContext.builder()
            .conversationId(DataAgentConstants.CONV_PREFIX_NL2SQL + "report-" + UUID.randomUUID())
            .userId(DataAgentConstants.SYSTEM_USER_ID)
            .permissionLevel(PermissionLevel.READ_ONLY)
            .messages(List.of(
                Message.builder().type(MessageType.USER).content(prompt).build()
            ))
            .build();

        try {
            String report = llmClient.chat(ctx).block().getContent();
            log.debug("ReportGeneration: {} chars", report != null ? report.length() : 0);
            return AgentResult.builder()
                .success(true)
                .output(report)
                .metadata(Map.of("reportLength", report != null ? report.length() : 0))
                .build();
        } catch (Exception e) {
            log.warn("ReportGeneration failed: {}", e.getMessage());
            return AgentResult.builder().success(false).error(e.getMessage()).build();
        }
    }
}
