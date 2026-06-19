package com.javacodeagent.core.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP (Model Context Protocol) 客户端接口。
 * 负责连接 MCP 服务器、发现工具定义、执行工具调用。
 */
public interface McpClient {

    /** 返回客户端标识（对应配置中的服务器名称）。 */
    String getName();

    /** 是否已成功连接到 MCP 服务器。 */
    boolean isConnected();

    /** 从 MCP 服务器发现并返回所有可用工具的定义。 */
    List<McpToolDefinition> listTools();

    /**
     * 调用 MCP 服务器上的工具。
     *
     * @param toolName 工具名称
     * @param arguments 工具参数
     * @return 工具执行结果（文本）
     */
    String callTool(String toolName, Map<String, Object> arguments);
}
