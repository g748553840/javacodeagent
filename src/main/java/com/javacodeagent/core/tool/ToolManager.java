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
import com.javacodeagent.piagent.tool.AbortSignal;
import com.javacodeagent.piagent.tool.AbortedException;
import com.javacodeagent.piagent.tool.ToolUpdateCallback;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
        return executeToolCall(toolCall, context, AbortSignal.NEVER, null);
    }

    /**
     * 执行工具调用，支持中止与流式进度。
     *
     * @param signal   中止信号，传 {@link AbortSignal#NEVER} 表示不可中止
     * @param onUpdate 流式进度回调，可为 null
     */
    public ToolExecutionResult executeToolCall(ToolCall toolCall,
                                               ExecutionContext context,
                                               AbortSignal signal,
                                               ToolUpdateCallback onUpdate) {
        Tool tool = tools.get(toolCall.getName());
        if (tool == null) {
            return ToolExecutionResult.error("Tool not found: " + toolCall.getName());
        }

        // 权限检查
        if (tool.requiresPermission()) {
            String userId = context.getUserId() != null ? context.getUserId() : "default";
            PermissionType requiredPermission = tool.getRequiredPermission();

            if (requiredPermission != null) {
                // ExecutionContext.permissionLevel 优先（ExploreAgent / 隔离 Agent 设置的 READ_ONLY 等）
                // 若为 null 则退化为 PermissionService 的用户级配置
                boolean allowed = (context.getPermissionLevel() != null)
                    ? permissionService.checkPermissionLevel(context.getPermissionLevel(), requiredPermission)
                    : permissionService.checkPermission(userId, requiredPermission);

                if (!allowed) {
                    log.warn("Permission denied for tool {}: {} required (level={})",
                        tool.getName(), requiredPermission, context.getPermissionLevel());
                    hookManager.triggerHook(HookType.PERMISSION_DENIED, HookContext.builder()
                        .type(HookType.PERMISSION_DENIED)
                        .userId(userId)
                        .conversationId(context.getConversationId())
                        .data(Map.of(
                            "toolName", tool.getName(),
                            "reason", requiredPermission + " required for " + tool.getName()
                        ))
                        .build());
                    return ToolExecutionResult.error(
                        "Permission denied: " + requiredPermission + " required for tool " + tool.getName()
                    );
                }
            }
        }

        // 参数适配：让工具有机会把 LLM 传来的旧格式参数转成自己期望的形态
        Map<String, Object> rawInput = toolCall.getInput() != null ? toolCall.getInput() : Map.of();
        Map<String, Object> input;
        try {
            Map<String, Object> prepared = tool.prepareArguments(rawInput);
            input = prepared != null ? prepared : rawInput;
        } catch (Exception e) {
            log.warn("prepareArguments failed for tool {}, using raw input", tool.getName(), e);
            input = rawInput;
        }

        // Pre-tool-call Hook
        HookContext preHookContext = HookContext.builder()
            .type(HookType.PRE_TOOL_CALL)
            .userId(context.getUserId())
            .conversationId(context.getConversationId())
            .data(Map.of("toolName", tool.getName(), "input", input))
            .build();
        HookResult preHookResult = hookManager.triggerHook(HookType.PRE_TOOL_CALL, preHookContext);
        if (!preHookResult.shouldContinue()) {
            return ToolExecutionResult.error("Tool execution rejected by hook: " + preHookResult.getMessage());
        }

        // 执行前先看一眼是否已被中止，避免做无用功
        if (signal != null && signal.isAborted()) {
            return ToolExecutionResult.error("Tool execution aborted before start");
        }

        try {
            AbortSignal effectiveSignal = signal != null ? signal : AbortSignal.NEVER;
            Map<String, Object> effectiveInput = input;
            ToolExecutionResult result;

            if (tool.isBlocking()) {
                // 阻塞工具（如 BashTool：process.waitFor()）必须在弹性线程池中运行，
                // 不能占用 Netty IO 事件循环线程，否则在响应式管道中会导致死锁。
                result = Mono.fromCallable(
                        () -> tool.execute(effectiveInput, context, effectiveSignal, onUpdate))
                    .subscribeOn(Schedulers.boundedElastic())
                    .block();
                if (result == null) {
                    result = ToolExecutionResult.error("Tool returned null result");
                }
            } else {
                result = tool.execute(effectiveInput, context, effectiveSignal, onUpdate);
            }

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
            // 中止是预期路径而非故障，单独识别以免污染错误日志
            if (isAbortion(e)) {
                log.info("Tool {} aborted by signal", tool.getName());
                return ToolExecutionResult.error("Tool execution aborted: " + tool.getName());
            }
            log.error("Tool execution failed: {}", tool.getName(), e);
            return ToolExecutionResult.error("Tool execution failed: " + e.getMessage());
        }
    }

    /** 识别中止异常，包括被 Reactor 包装后藏在 cause 链里的情形。 */
    private boolean isAbortion(Throwable e) {
        Throwable cursor = e;
        int depth = 0;
        while (cursor != null && depth < 5) {
            if (cursor instanceof AbortedException) {
                return true;
            }
            cursor = cursor.getCause();
            depth++;
        }
        return false;
    }

    /**
     * 查询工具是否可用
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }
}
