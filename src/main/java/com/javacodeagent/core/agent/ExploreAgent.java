package com.javacodeagent.core.agent;

import com.javacodeagent.core.enums.PermissionLevel;
import com.javacodeagent.core.tool.ToolManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Explore Agent - 只读代码搜索
 * 只能使用读取类工具，不允许写操作
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExploreAgent implements Agent {

    /**
     * 只读工具列表
     */
    private static final List<String> READ_ONLY_TOOLS = List.of(
        "Read", "Glob", "Grep", "List"
    );

    private final ToolManager toolManager;

    @Override
    public String getType() {
        return "explore";
    }

    @Override
    public String getDescription() {
        return "Read-only code exploration and search agent. Can read files, search patterns, and list directories.";
    }

    @Override
    public List<String> getAvailableTools() {
        return READ_ONLY_TOOLS;
    }

    @Override
    public AgentResult process(AgentTask task, AgentContext context) {
        log.info("ExploreAgent processing task: {}", task.getDescription());

        // 确保只读权限
        if (context.getExecutionContext() != null) {
            context.getExecutionContext().setPermissionLevel(PermissionLevel.READ_ONLY);
        }

        StringBuilder output = new StringBuilder();
        output.append("Explore results for: ").append(task.getDescription()).append("\n\n");

        // 探索任务通过检查可用的只读工具来执行
        for (String toolName : READ_ONLY_TOOLS) {
            if (toolManager.hasTool(toolName)) {
                output.append("- Available tool: ").append(toolName).append("\n");
            }
        }

        return AgentResult.builder()
            .output(output.toString())
            .success(true)
            .build();
    }
}
