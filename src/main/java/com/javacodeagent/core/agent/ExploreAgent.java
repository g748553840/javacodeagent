package com.javacodeagent.core.agent;

import com.javacodeagent.core.enums.PermissionLevel;
import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.core.model.ToolCall;
import com.javacodeagent.core.model.ToolExecutionResult;
import com.javacodeagent.core.tool.ToolManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Explore Agent — 只读代码搜索 Agent。
 *
 * 接收探索任务（task.parameters 中的 pattern / path / type），
 * 使用 Glob / Grep / Read / List 工具实际执行搜索，返回结构化结果。
 *
 * 强制运行在 READ_ONLY 权限下，无法触发写操作。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExploreAgent implements Agent {

    private static final List<String> READ_ONLY_TOOLS = List.of("Read", "Glob", "Grep", "List");

    private final ToolManager toolManager;

    @Override
    public String getType() { return "explore"; }

    @Override
    public String getDescription() {
        return "Read-only code exploration agent. Searches files by glob pattern or grep, reads file contents.";
    }

    @Override
    public List<String> getAvailableTools() { return READ_ONLY_TOOLS; }

    @Override
    public AgentResult process(AgentTask task, AgentContext context) {
        log.info("ExploreAgent processing: {}", task.getDescription());

        // 强制只读权限
        ExecutionContext execCtx = buildReadOnlyContext(context);

        Map<String, Object> params = task.getParameters() != null ? task.getParameters() : Map.of();
        String mode = (String) params.getOrDefault("mode", "glob");

        return switch (mode) {
            case "glob" -> runGlob(params, execCtx, task.getDescription());
            case "grep" -> runGrep(params, execCtx, task.getDescription());
            case "read" -> runRead(params, execCtx, task.getDescription());
            case "list" -> runList(params, execCtx, task.getDescription());
            default    -> {
                log.warn("ExploreAgent: unknown mode '{}', falling back to glob", mode);
                yield runGlob(params, execCtx, task.getDescription());
            }
        };
    }

    // -------------------------------------------------------------------------

    private AgentResult runGlob(Map<String, Object> params, ExecutionContext ctx, String desc) {
        String pattern = (String) params.getOrDefault("pattern", "**/*.java");
        String path    = (String) params.getOrDefault("path", ".");

        ToolCall call = ToolCall.builder()
            .id(UUID.randomUUID().toString())
            .name("Glob")
            .input(Map.of("pattern", pattern, "path", path))
            .build();

        ToolExecutionResult result = toolManager.executeToolCall(call, ctx);
        return toAgentResult(result, "Glob[" + pattern + "]", desc);
    }

    private AgentResult runGrep(Map<String, Object> params, ExecutionContext ctx, String desc) {
        String pattern = (String) params.getOrDefault("pattern", "");
        String path    = (String) params.getOrDefault("path", ".");
        String type    = (String) params.getOrDefault("type", "");

        Map<String, Object> input = type.isBlank()
            ? Map.of("pattern", pattern, "path", path)
            : Map.of("pattern", pattern, "path", path, "type", type);

        ToolCall call = ToolCall.builder()
            .id(UUID.randomUUID().toString())
            .name("Grep")
            .input(input)
            .build();

        ToolExecutionResult result = toolManager.executeToolCall(call, ctx);
        return toAgentResult(result, "Grep[" + pattern + "]", desc);
    }

    private AgentResult runRead(Map<String, Object> params, ExecutionContext ctx, String desc) {
        String filePath = (String) params.getOrDefault("file_path", "");
        if (filePath.isEmpty()) {
            return AgentResult.builder()
                .output("ExploreAgent: file_path parameter is required for read mode")
                .success(false)
                .error("Missing file_path")
                .build();
        }

        Map<String, Object> input = Map.of("file_path", filePath);
        ToolCall call = ToolCall.builder()
            .id(UUID.randomUUID().toString())
            .name("Read")
            .input(input)
            .build();

        ToolExecutionResult result = toolManager.executeToolCall(call, ctx);
        return toAgentResult(result, "Read[" + filePath + "]", desc);
    }

    private AgentResult runList(Map<String, Object> params, ExecutionContext ctx, String desc) {
        String path = (String) params.getOrDefault("path", ".");

        ToolCall call = ToolCall.builder()
            .id(UUID.randomUUID().toString())
            .name("List")
            .input(Map.of("path", path))
            .build();

        ToolExecutionResult result = toolManager.executeToolCall(call, ctx);
        return toAgentResult(result, "List[" + path + "]", desc);
    }

    // -------------------------------------------------------------------------

    private AgentResult toAgentResult(ToolExecutionResult result, String toolLabel, String taskDesc) {
        if (result.isSuccess()) {
            String output = "ExploreAgent [" + taskDesc + "] via " + toolLabel + ":\n"
                + (result.getContent() != null ? result.getContent() : "(no output)");
            return AgentResult.builder()
                .output(output)
                .success(true)
                .build();
        } else {
            return AgentResult.builder()
                .output("ExploreAgent failed: " + result.getError())
                .success(false)
                .error(result.getError())
                .build();
        }
    }

    private ExecutionContext buildReadOnlyContext(AgentContext context) {
        ExecutionContext base = context.getExecutionContext();
        return ExecutionContext.builder()
            .userId(base != null ? base.getUserId() : "system")
            .conversationId(base != null ? base.getConversationId() : null)
            .permissionLevel(PermissionLevel.READ_ONLY)
            .workingDirectory(base != null ? base.getWorkingDirectory() : null)
            .build();
    }
}
