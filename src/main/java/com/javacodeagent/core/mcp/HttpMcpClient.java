package com.javacodeagent.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 HTTP/SSE 的 MCP 客户端实现（Streamable HTTP 传输层）。
 *
 * MCP 规范参考：https://modelcontextprotocol.io/docs/concepts/transports
 *
 * 此实现采用 Streamable HTTP 模式：
 *   POST /  → 请求，响应为 JSON（或 SSE 流）
 *
 * 每次调用均通过 JSON-RPC 2.0 格式发送请求体。
 */
@Slf4j
public class HttpMcpClient implements McpClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private final String name;
    private final String serverUrl;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private volatile boolean connected = false;

    public HttpMcpClient(String name, String serverUrl, WebClient webClient, ObjectMapper objectMapper) {
        this.name = name;
        this.serverUrl = serverUrl.replaceAll("/+$", "");
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() { return name; }

    @Override
    public boolean isConnected() { return connected; }

    /** 探测 MCP 服务器是否可用（发送 initialize 请求）。 */
    public boolean connect() {
        try {
            String resp = rpc("initialize", Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "javacodeagent", "version", "1.0")
            ));
            connected = resp != null && !resp.isEmpty();
            log.info("MCP server '{}' connect: {}", name, connected ? "OK" : "FAILED");
        } catch (Exception e) {
            log.warn("MCP server '{}' unreachable: {}", name, e.getMessage());
            connected = false;
        }
        return connected;
    }

    @Override
    public List<McpToolDefinition> listTools() {
        List<McpToolDefinition> result = new ArrayList<>();
        try {
            String resp = rpc("tools/list", Map.of());
            JsonNode root = objectMapper.readTree(resp);
            JsonNode tools = root.path("result").path("tools");
            if (tools.isArray()) {
                for (JsonNode t : tools) {
                    result.add(McpToolDefinition.builder()
                        .name(t.path("name").asText())
                        .description(t.path("description").asText(""))
                        .inputSchema(objectMapper.convertValue(t.path("inputSchema"), Map.class))
                        .build());
                }
            }
        } catch (Exception e) {
            log.error("Failed to list tools from MCP server '{}': {}", name, e.getMessage());
        }
        return result;
    }

    @Override
    public String callTool(String toolName, Map<String, Object> arguments) {
        try {
            String resp = rpc("tools/call", Map.of(
                "name", toolName,
                "arguments", arguments != null ? arguments : Map.of()
            ));
            JsonNode root = objectMapper.readTree(resp);
            JsonNode content = root.path("result").path("content");
            if (content.isArray() && content.size() > 0) {
                return content.get(0).path("text").asText("");
            }
            return root.path("result").toString();
        } catch (Exception e) {
            log.error("MCP tool call failed: {}.{}({}): {}", name, toolName, arguments, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------

    /** 发送 JSON-RPC 2.0 请求并返回响应体字符串。 */
    private String rpc(String method, Object params) {
        Map<String, Object> body = Map.of(
            "jsonrpc", "2.0",
            "id", System.nanoTime(),
            "method", method,
            "params", params
        );
        return webClient.post()
            .uri(serverUrl)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(TIMEOUT)
            .block();
    }
}
