package com.javacodeagent.core.mcp;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/** MCP 工具定义（从 tools/list 响应中解析）。 */
@Data
@Builder
public class McpToolDefinition {
    private String name;
    private String description;
    private Map<String, Object> inputSchema;
}
