package com.javacodeagent.core.tool;

import com.javacodeagent.core.enums.PermissionType;
import com.javacodeagent.core.hook.HookContext;
import com.javacodeagent.core.hook.HookManager;
import com.javacodeagent.core.hook.HookResult;
import com.javacodeagent.core.hook.HookType;
import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.core.model.ToolCall;
import com.javacodeagent.core.model.ToolDefinition;
import com.javacodeagent.core.model.ToolExecutionResult;
import com.javacodeagent.core.permission.PermissionService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolManager {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final List<Tool> toolBeans;
    private final PermissionService permissionService;
    private final HookManager hookManager;

    @PostConstruct
    public void init() {
        for (Tool tool : toolBeans) {
            registerTool(tool);
        }
    }

    public void registerTool(Tool tool) {
        tools.put(tool.getName(), tool);
        log.info("Registered tool: {}", tool.getName());
    }

    public List<ToolDefinition> getAllToolDefinitions() {
        return tools.values().stream()
            .map(Tool::toDefinition)
            .toList();
    }

    public ToolExecutionResult executeToolCall(ToolCall toolCall, ExecutionContext context) {
        Tool tool = tools.get(toolCall.getName());
        if (tool == null) {
            return ToolExecutionResult.error("Tool not found: " + toolCall.getName());
        }

        // 权限检查
        if (tool.requiresPermission()) {
            String userId = context.getUserId() != null ? context.getUserId() : "default";
            PermissionType requiredPermission = tool.getRequiredPermission();

            if (requiredPermission != null && !permissionService.checkPermission(userId, requiredPermission)) {
                log.warn("Permission denied for tool {}: {} required", tool.getName(), requiredPermission);
                return ToolExecutionResult.error(
                    "Permission denied: " + requiredPermission + " required for tool " + tool.getName()
                );
            }
        }

        // Pre-tool-call Hook
        HookContext preHookContext = HookContext.builder()
            .type(HookType.PRE_TOOL_CALL)
            .userId(context.getUserId())
            .conversationId(context.getConversationId())
            .data(Map.of("toolName", tool.getName(), "input", toolCall.getInput()))
            .build();
        HookResult preHookResult = hookManager.triggerHook(HookType.PRE_TOOL_CALL, preHookContext);
        if (!preHookResult.shouldContinue()) {
            return ToolExecutionResult.error("Tool execution rejected by hook: " + preHookResult.getMessage());
        }

        try {
            Map<String, Object> input = toolCall.getInput();
            ToolExecutionResult result = tool.execute(input, context);

            // Post-tool-call Hook
            HookContext postHookContext = HookContext.builder()
                .type(HookType.POST_TOOL_CALL)
                .userId(context.getUserId())
                .conversationId(context.getConversationId())
                .data(Map.of("toolName", tool.getName(), "result", result.isSuccess() ? "success" : "failed"))
                .build();
            hookManager.triggerHook(HookType.POST_TOOL_CALL, postHookContext);

            return result;
        } catch (Exception e) {
            log.error("Tool execution failed: {}", tool.getName(), e);
            return ToolExecutionResult.error("Tool execution failed: " + e.getMessage());
        }
    }

    /**
     * 查询工具是否可用
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }
}
