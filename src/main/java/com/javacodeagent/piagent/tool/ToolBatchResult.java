package com.javacodeagent.piagent.tool;

import com.javacodeagent.core.model.Message;
import com.javacodeagent.core.model.ToolExecutionResult;

import java.util.List;

/**
 * 一批工具调用的执行结果。
 *
 * @param messages  按 assistant 声明顺序排列的 tool_result 消息
 * @param results   与 messages 一一对应的原始执行结果
 * @param terminate 是否应停止后续 LLM 调用
 */
public record ToolBatchResult(
    List<Message> messages,
    List<ToolExecutionResult> results,
    boolean terminate
) {

    /**
     * 判定整批是否应终止后续 LLM 调用。
     *
     * <p><b>「全部一致」原则</b>（对齐 pi 的 {@code shouldTerminateToolBatch}）：
     * 只有当**所有**工具都要求 terminate 时才真正终止。
     *
     * <p>为什么不是「任一为真」：考虑批次
     * {@code [exit_plan_mode(terminate=true), read_file(terminate=false)]}。
     * 若按任一为真终止，{@code read_file} 的结果永远不会被模型看到——
     * 它的执行完全浪费，用户也拿不到那份数据。全部一致保证只有当
     * 每个工具都明确表示"到此为止"时才停下。
     *
     * <p>空批次返回 false（没有工具要求终止）。
     */
    public static boolean shouldTerminate(List<ToolExecutionResult> results) {
        return results != null
            && !results.isEmpty()
            && results.stream().allMatch(ToolExecutionResult::isTerminate);
    }
}
