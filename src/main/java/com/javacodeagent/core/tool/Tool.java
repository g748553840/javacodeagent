package com.javacodeagent.core.tool;

import com.javacodeagent.core.enums.PermissionType;
import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.core.model.ToolDefinition;
import com.javacodeagent.core.model.ToolExecutionResult;
import com.javacodeagent.piagent.tool.AbortSignal;
import com.javacodeagent.piagent.tool.ToolUpdateCallback;

import java.util.Map;

public interface Tool {
    String getName();

    String getDescription();

    Map<String, Object> getParameterSchema();

    ToolExecutionResult execute(Map<String, Object> input, ExecutionContext context);

    default boolean requiresPermission() {
        return false;
    }

    default PermissionType getRequiredPermission() {
        return null;
    }

    /**
     * 标记该工具是否包含同步阻塞操作（如 process.waitFor()、文件 IO 等）。
     * 若为 {@code true}，{@link ToolManager} 会将执行切换到
     * {@code Schedulers.boundedElastic()} 线程，避免阻塞 Netty IO 事件循环。
     * 默认 {@code false}（大多数工具的文件操作量很小，无需额外切换）。
     */
    default boolean isBlocking() {
        return false;
    }

    /**
     * 参数兼容性适配。
     *
     * <p>对应 pi 的 {@code prepareArguments}。当 LLM 传来的参数名与工具期望的
     * 不一致时（例如模型习惯性地用 {@code path} 而工具定义的是 {@code file_path}），
     * 在此做一次转换，避免让工具主体代码去处理各种历史格式。
     *
     * <p>默认不转换。
     */
    default Map<String, Object> prepareArguments(Map<String, Object> raw) {
        return raw;
    }

    /**
     * 支持中止与流式进度的执行入口。
     *
     * <p>默认实现直接委托给 {@link #execute(Map, ExecutionContext)}，
     * 因此所有既有工具无需任何改动即可继续工作。只有真正需要
     * 「可被中止」或「边执行边推送输出」能力的工具才覆盖此方法。
     *
     * @param signal   中止信号；实现应周期性调用 {@link AbortSignal#throwIfAborted()}
     *                 或通过 {@link AbortSignal#onAbort} 注册清理动作
     * @param onUpdate 流式进度回调，可能为 {@code null}
     */
    default ToolExecutionResult execute(Map<String, Object> input,
                                        ExecutionContext context,
                                        AbortSignal signal,
                                        ToolUpdateCallback onUpdate) {
        return execute(input, context);
    }

    default ToolDefinition toDefinition() {
        return ToolDefinition.builder()
            .name(getName())
            .description(getDescription())
            .inputSchema(getParameterSchema())
            .build();
    }
}