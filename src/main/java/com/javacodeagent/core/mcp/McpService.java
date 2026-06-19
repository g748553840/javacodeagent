package com.javacodeagent.core.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javacodeagent.core.enums.PermissionType;
import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.core.model.ToolExecutionResult;
import com.javacodeagent.core.tool.Tool;
import com.javacodeagent.core.tool.ToolManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务管理器。
 * 启动时根据配置连接所有 MCP 服务器，并为每个发现的工具注册代理 Tool 实例，
 * 供 ToolManager 自动发现和调用。
 *
 * 配置示例（application.yml）：
 *   mcp:
 *     servers:
 *       - name: filesystem
 *         url: http://localhost:3001
 *       - name: github
 *         url: http://localhost:3002
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    /**
     * 注入 ToolManager，用于在 MCP 服务器发现工具后自动注册代理工具。
     * McpService 与 ToolManager 互相不依赖核心业务逻辑，不存在循环依赖。
     */
    private final ToolManager toolManager;

    @Value("${mcp.enabled:false}")
    private boolean mcpEnabled;

    @Value("${mcp.servers:}")
    private String serversConfig;

    private final List<McpClient> clients = new ArrayList<>();
    private final List<Tool> mcpTools = new ArrayList<>();

    @PostConstruct
    public void init() {
        if (!mcpEnabled) {
            log.info("MCP support disabled (mcp.enabled=false)");
            return;
        }
        // serversConfig 是逗号分隔的 name=url 列表，例如 "fs=http://localhost:3001,gh=http://localhost:3002"
        if (serversConfig == null || serversConfig.isBlank()) return;

        for (String entry : serversConfig.split(",")) {
            String[] parts = entry.trim().split("=", 2);
            if (parts.length != 2) continue;
            String name = parts[0].trim();
            String url  = parts[1].trim();

            HttpMcpClient client = new HttpMcpClient(name, url, webClient, objectMapper);
            if (client.connect()) {
                clients.add(client);
                registerToolsFrom(client);
            }
        }
        log.info("MCP initialized: {} server(s), {} tool(s)", clients.size(), mcpTools.size());
    }

    /** 返回所有从 MCP 服务器发现的代理 Tool，外部查询用（ToolManager 内部已直接注册）。 */
    public List<Tool> getMcpTools() {
        return List.copyOf(mcpTools);
    }

    // -------------------------------------------------------------------------

    private void registerToolsFrom(McpClient client) {
        for (McpToolDefinition def : client.listTools()) {
            McpProxyTool proxyTool = new McpProxyTool(client, def);
            mcpTools.add(proxyTool);
            // 直接注册到 ToolManager，使 LLM 可以调用 MCP 工具
            toolManager.registerTool(proxyTool);
            log.info("Registered MCP tool: {}/{}", client.getName(), def.getName());
        }
    }

    // -------------------------------------------------------------------------

    /** 代理工具：将 ToolManager 的调用转发到 MCP 服务器。 */
    private static class McpProxyTool implements Tool {

        private final McpClient client;
        private final McpToolDefinition def;

        McpProxyTool(McpClient client, McpToolDefinition def) {
            this.client = client;
            this.def = def;
        }

        @Override public String getName() { return "mcp__" + client.getName() + "__" + def.getName(); }
        @Override public String getDescription() { return "[MCP:" + client.getName() + "] " + def.getDescription(); }
        @Override public Map<String, Object> getParameterSchema() {
            return def.getInputSchema() != null ? def.getInputSchema() : Map.of("type", "object");
        }
        @Override public boolean requiresPermission() { return true; }
        @Override public PermissionType getRequiredPermission() { return PermissionType.NETWORK_REQUEST; }

        @Override
        public ToolExecutionResult execute(Map<String, Object> input, ExecutionContext context) {
            try {
                String result = client.callTool(def.getName(), input);
                return ToolExecutionResult.builder().success(true).content(result).build();
            } catch (Exception e) {
                return ToolExecutionResult.builder().success(false).error(e.getMessage()).build();
            }
        }
    }
}
