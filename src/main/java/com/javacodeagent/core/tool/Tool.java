package com.javacodeagent.core.tool;

import com.javacodeagent.core.enums.PermissionType;
import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.core.model.ToolDefinition;
import com.javacodeagent.core.model.ToolExecutionResult;

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

    default ToolDefinition toDefinition() {
        return ToolDefinition.builder()
            .name(getName())
            .description(getDescription())
            .inputSchema(getParameterSchema())
            .build();
    }
}