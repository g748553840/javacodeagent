package com.javacodeagent.core.model;

import com.javacodeagent.core.enums.ToolStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionResult {
    private String content;
    private boolean success;
    private String error;
    private Map<String, Object> metadata;
    private ToolStatus status;

    /**
     * 提示 Agentic Loop 停止后续 LLM 调用。
     *
     * <p>对应 pi 的 {@code AgentToolResult.terminate}。用于像 {@code exit_plan_mode}
     * 这类需要把控制权交还给用户的工具——工具执行成功了，但不该再让模型继续自主行动。
     *
     * <p>注意 pi 的「全部一致」原则：一批工具调用中只有<b>所有</b>结果都
     * {@code terminate=true} 时才真正终止。否则批次里其他工具的执行结果
     * 就永远不会被模型看到，等于白做。
     */
    @Builder.Default
    private boolean terminate = false;

    /**
     * 结构化执行细节，仅供 UI 渲染，不发送给 LLM。
     *
     * <p>对应 pi 的 {@code AgentToolResult.details}。典型用途：完整的命令输出
     * （{@code content} 里放的是截断版）、文件 diff 的结构化表示、
     * 查询结果的完整行集。把它与 {@code content} 分开，
     * 既能给模型精简的文本，又不丢失前端展示所需的完整数据。
     */
    private Object details;

    public static ToolExecutionResult success(String content) {
        return ToolExecutionResult.builder()
            .content(content)
            .success(true)
            .status(ToolStatus.COMPLETED)
            .build();
    }

    public static ToolExecutionResult error(String error) {
        return ToolExecutionResult.builder()
            .error(error)
            .success(false)
            .status(ToolStatus.FAILED)
            .build();
    }

    /** 构造一个要求停止后续 LLM 调用的成功结果。 */
    public static ToolExecutionResult successAndTerminate(String content) {
        return ToolExecutionResult.builder()
            .content(content)
            .success(true)
            .status(ToolStatus.COMPLETED)
            .terminate(true)
            .build();
    }
}