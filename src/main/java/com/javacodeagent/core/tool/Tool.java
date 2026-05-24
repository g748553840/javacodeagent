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

    default ToolDefinition toDefinition() {
        return ToolDefinition.builder()
            .name(getName())
            .description(getDescription())
            .inputSchema(getParameterSchema())
            .build();
    }
}