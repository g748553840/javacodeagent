package com.javacodeagent.core.llm;

import com.javacodeagent.config.LLMConfig;
import com.javacodeagent.core.model.ConversationContext;
import com.javacodeagent.core.model.LLMResponse;
import com.javacodeagent.core.model.Message;
import com.javacodeagent.core.model.ToolCall;
import com.javacodeagent.core.model.ToolDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容格式 LLM 客户端。
 * 支持 OpenAI、DeepSeek、GLM (ChatGLM)、Qwen 及其他 OpenAI 兼容接口。
 * 由 LLMClientConfig 工厂按 llm.provider != anthropic 时创建。
 */
@Slf4j
public class OpenAILLMClient implements LLMClient {

    private static final int SSE_DATA_PREFIX_LENGTH = 6; // "data: ".length()

    private final WebClient webClient;
    private final LLMConfig config;
    private final ObjectMapper objectMapper;

    public OpenAILLMClient(WebClient webClient, LLMConfig config, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<LLMResponse> chat(ConversationContext context) {
        Map<String, Object> requestBody = buildRequestBody(context, false);

        return webClient.post()
            .uri(resolveChatUrl())
            .headers(this::setCommonHeaders)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(String.class)
            .map(this::parseResponse)
            .onErrorResume(e -> {
                log.error("OpenAI-compat LLM request failed", e);
                return Mono.just(LLMResponse.builder()
                    .content("Error: " + e.getMessage())
                    .build());
            });
    }

    @Override
    public Flux<String> chatStream(ConversationContext context) {
        Map<String, Object> requestBody = buildRequestBody(context, true);

        return webClient.post()
            .uri(resolveChatUrl())
            .headers(headers -> {
                setCommonHeaders(headers);
                headers.set("Accept", "text/event-stream");
            })
            .bodyValue(requestBody)
            .retrieve()
            .bodyToFlux(String.class)
            .map(this::parseStreamEvent)
            .filter(s -> !s.isEmpty());
    }

    // -------------------------------------------------------------------------
    // 请求构造
    // -------------------------------------------------------------------------

    private Map<String, Object> buildRequestBody(ConversationContext context, boolean stream) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", config.getModel());
        body.put("max_tokens", config.getMaxTokens());

        List<Map<String, Object>> messages = buildMessages(context);
        body.put("messages", messages);

        if (stream) {
            body.put("stream", true);
        }
        if (config.getTemperature() > 0) {
            body.put("temperature", config.getTemperature());
        }
        if (context.getAvailableTools() != null && !context.getAvailableTools().isEmpty()) {
            body.put("tools", convertTools(context.getAvailableTools()));
            body.put("tool_choice", "auto");
        }
        return body;
    }

    /**
     * 将内部 Message 列表转换为 OpenAI 消息格式。
     * System prompt 作为首条 system 角色消息注入。
     */
    private List<Map<String, Object>> buildMessages(ConversationContext context) {
        List<Map<String, Object>> result = new ArrayList<>();

        // 系统提示词作为 system 角色消息
        if (config.getSystemPrompt() != null && !config.getSystemPrompt().isEmpty()) {
            result.add(Map.of("role", "system", "content", config.getSystemPrompt()));
        }

        for (Message msg : context.getMessages()) {
            switch (msg.getType()) {
                case SYSTEM -> result.add(Map.of("role", "system", "content",
                    msg.getContent() != null ? msg.getContent() : ""));

                case USER -> result.add(Map.of("role", "user", "content",
                    msg.getContent() != null ? msg.getContent() : ""));

                case ASSISTANT -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("role", "assistant");
                    // 若有 tool_calls，content 可为 null
                    if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                        m.put("content", msg.getContent()); // OpenAI 允许 null
                        List<Map<String, Object>> toolCalls = new ArrayList<>();
                        for (ToolCall tc : msg.getToolCalls()) {
                            Map<String, Object> fn = new HashMap<>();
                            fn.put("name", tc.getName());
                            try {
                                fn.put("arguments", objectMapper.writeValueAsString(tc.getInput()));
                            } catch (Exception e) {
                                fn.put("arguments", "{}");
                            }
                            toolCalls.add(Map.of(
                                "id", tc.getId(),
                                "type", "function",
                                "function", fn
                            ));
                        }
                        m.put("tool_calls", toolCalls);
                    } else {
                        m.put("content", msg.getContent() != null ? msg.getContent() : "");
                    }
                    result.add(m);
                }

                case TOOL_RESULT -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("role", "tool");
                    m.put("tool_call_id", msg.getToolCallId() != null ? msg.getToolCallId() : "");
                    m.put("content", msg.getContent() != null ? msg.getContent() : "");
                    result.add(m);
                }
            }
        }
        return result;
    }

    /**
     * OpenAI 工具格式：{"type":"function","function":{"name","description","parameters"}}
     */
    private List<Map<String, Object>> convertTools(List<ToolDefinition> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ToolDefinition tool : tools) {
            result.add(Map.of(
                "type", "function",
                "function", Map.of(
                    "name", tool.getName(),
                    "description", tool.getDescription(),
                    "parameters", tool.getInputSchema()
                )
            ));
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // 响应解析
    // -------------------------------------------------------------------------

    private LLMResponse parseResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.has("error")) {
                String msg = root.path("error").path("message").asText("Unknown API error");
                log.error("OpenAI-compat API error: {}", msg);
                return LLMResponse.builder().content("API Error: " + msg).build();
            }

            JsonNode choice = root.path("choices").path(0);
            JsonNode message = choice.path("message");
            String finishReason = choice.path("finish_reason").asText("");

            String content = message.path("content").isNull()
                ? "" : message.path("content").asText("");

            List<ToolCall> toolCalls = new ArrayList<>();
            if (message.has("tool_calls") && message.path("tool_calls").isArray()) {
                for (JsonNode tc : message.path("tool_calls")) {
                    JsonNode fn = tc.path("function");
                    Map<String, Object> input;
                    try {
                        input = objectMapper.readValue(fn.path("arguments").asText("{}"),
                            new TypeReference<Map<String, Object>>() {});
                    } catch (Exception e) {
                        input = new HashMap<>();
                    }
                    toolCalls.add(ToolCall.builder()
                        .id(tc.path("id").asText())
                        .name(fn.path("name").asText())
                        .input(input)
                        .build());
                }
            }

            // 将 OpenAI stop_reason 映射为内部格式
            String stopReason = switch (finishReason) {
                case "tool_calls" -> "tool_use";
                case "stop" -> "end_turn";
                default -> finishReason;
            };

            return LLMResponse.builder()
                .id(root.path("id").asText())
                .model(root.path("model").asText())
                .content(content)
                .toolCalls(toolCalls.isEmpty() ? null : toolCalls)
                .stopReason(stopReason)
                .build();
        } catch (Exception e) {
            log.error("Failed to parse OpenAI-compat response: {}", body, e);
            return LLMResponse.builder().content("Parse error: " + e.getMessage()).build();
        }
    }

    /**
     * 结构化流式：逐 Token 发出文本块，遇到工具调用时按 index 分组组装，
     * finish_reason 出现时发出完整工具调用 + DONE 信号。
     *
     * OpenAI SSE tool_calls delta 格式（工具调用场景）：
     *   data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_01","type":"function","function":{"name":"Read","arguments":""}}]},"finish_reason":null}]}
     *   data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"file_path\":"}}]},"finish_reason":null}]}
     *   data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"/src/Main.java\"}"}}]},"finish_reason":null}]}
     *   data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}
     *   data: [DONE]
     */
    @Override
    public Flux<LLMStreamChunk> chatStreamFull(ConversationContext context) {
        Map<String, Object> requestBody = buildRequestBody(context, true);

        // concatMap 保证串行处理，无需 ConcurrentHashMap
        Map<Integer, ToolCallAccum> accumMap = new HashMap<>();

        return webClient.post()
            .uri(resolveChatUrl())
            .headers(headers -> {
                setCommonHeaders(headers);
                headers.set("Accept", "text/event-stream");
            })
            .bodyValue(requestBody)
            .retrieve()
            .bodyToFlux(String.class)
            // SSE 本身是有序流，使用 concatMap 保证逐行串行处理，防止 accumMap 竞态
            .concatMap(line -> parseOpenAiChunk(line, accumMap))
            .onErrorResume(e -> {
                log.error("OpenAI-compat stream failed", e);
                return Flux.just(LLMStreamChunk.error(e.getMessage()));
            });
    }

    /**
     * 解析单行 SSE 数据，返回 0-N 个 LLMStreamChunk。
     *
     * 文本 delta → 立即 emit TEXT chunk
     * tool_calls delta → 按 index 追加到 accumMap
     * finish_reason 非 null → 组装所有积累的工具调用 emit TOOL_CALL chunk + DONE chunk
     */
    private Flux<LLMStreamChunk> parseOpenAiChunk(String line, Map<Integer, ToolCallAccum> accumMap) {
        if (line == null || !line.startsWith("data: ")) return Flux.empty();
        try {
            String data = line.substring(SSE_DATA_PREFIX_LENGTH).trim();
            if ("[DONE]".equals(data)) return Flux.empty();

            JsonNode root = objectMapper.readTree(data);
            JsonNode choice = root.path("choices").path(0);
            JsonNode delta = choice.path("delta");
            String finishReason = choice.path("finish_reason").isNull()
                ? null : choice.path("finish_reason").asText(null);

            List<LLMStreamChunk> chunks = new ArrayList<>();

            // 1. 处理文本 delta
            if (delta.has("content") && !delta.path("content").isNull()) {
                String text = delta.path("content").asText("");
                if (!text.isEmpty()) {
                    chunks.add(LLMStreamChunk.text(text));
                }
            }

            // 2. 处理 tool_calls delta（按 index 分组累积）
            if (delta.has("tool_calls") && delta.path("tool_calls").isArray()) {
                for (JsonNode tcNode : delta.path("tool_calls")) {
                    int index = tcNode.path("index").asInt(0);
                    ToolCallAccum accum = accumMap.computeIfAbsent(index, i -> new ToolCallAccum());

                    // 首次出现 id/name
                    if (tcNode.has("id") && !tcNode.path("id").asText("").isEmpty()) {
                        accum.id = tcNode.path("id").asText();
                    }
                    if (tcNode.has("type") && !tcNode.path("type").asText("").isEmpty()) {
                        accum.type = tcNode.path("type").asText();
                    }
                    JsonNode fn = tcNode.path("function");
                    if (fn.has("name") && !fn.path("name").asText("").isEmpty()) {
                        accum.name = fn.path("name").asText();
                    }
                    if (fn.has("arguments")) {
                        accum.argumentsAccum.append(fn.path("arguments").asText(""));
                    }
                }
            }

            // 3. finish_reason 到达时组装完整工具调用
            if (finishReason != null && !finishReason.isEmpty()) {
                // 将 OpenAI stop_reason 映射为内部格式
                String stopReason = switch (finishReason) {
                    case "tool_calls" -> "tool_use";
                    case "stop"       -> "end_turn";
                    default           -> finishReason;
                };

                // 发出所有组装好的工具调用
                for (Map.Entry<Integer, ToolCallAccum> entry : accumMap.entrySet()) {
                    ToolCallAccum accum = entry.getValue();
                    if (accum.name != null && !accum.name.isEmpty()) {
                        Map<String, Object> input;
                        try {
                            input = objectMapper.readValue(accum.argumentsAccum.toString(),
                                new TypeReference<Map<String, Object>>() {});
                        } catch (Exception e) {
                            // arguments 不完整或不是 JSON 时退化为 raw 字符串
                            input = Map.of("_raw", accum.argumentsAccum.toString());
                        }
                        ToolCall tc = ToolCall.builder()
                            .id(accum.id != null ? accum.id : "call_" + entry.getKey())
                            .name(accum.name)
                            .input(input)
                            .build();
                        chunks.add(LLMStreamChunk.toolCall(tc));
                    }
                }
                accumMap.clear();
                chunks.add(LLMStreamChunk.done(stopReason));
            }

            return chunks.isEmpty() ? Flux.empty() : Flux.fromIterable(chunks);

        } catch (Exception e) {
            log.debug("Skipping unparseable OpenAI SSE line: {}", line);
            return Flux.empty();
        }
    }

    /** 单个工具调用的流式积累状态 */
    private static class ToolCallAccum {
        String id;
        String type;
        String name;
        StringBuilder argumentsAccum = new StringBuilder();
    }

    /**
     * 原始文本流（chatStream 接口）。
     * OpenAI SSE delta 格式：
     *   data: {"choices":[{"delta":{"content":"..."},"finish_reason":null}]}
     *   data: [DONE]
     */
    private String parseStreamEvent(String line) {
        if (line == null || !line.startsWith("data: ")) return "";
        try {
            String data = line.substring(SSE_DATA_PREFIX_LENGTH).trim();
            if ("[DONE]".equals(data)) return "";
            JsonNode root = objectMapper.readTree(data);
            JsonNode delta = root.path("choices").path(0).path("delta");
            if (delta.has("content") && !delta.path("content").isNull()) {
                return delta.path("content").asText("");
            }
        } catch (Exception e) {
            log.debug("Skipping unparseable OpenAI SSE line: {}", line);
        }
        return "";
    }

    // -------------------------------------------------------------------------
    // 端点解析
    // -------------------------------------------------------------------------

    /**
     * 根据 provider 或用户配置的 endpoint 推断完整 chat completions URL。
     * 用户若配置了 endpoint 则直接使用，否则按 provider 名选择默认地址。
     */
    private String resolveChatUrl() {
        String base = config.getEndpoint();

        // 用户未配置 endpoint 时按 provider 选默认地址
        if (base == null || base.isEmpty()) {
            base = switch (config.getProvider() != null ? config.getProvider().toLowerCase() : "") {
                case "deepseek" -> "https://api.deepseek.com/v1";
                case "glm", "chatglm", "zhipu" -> "https://open.bigmodel.cn/api/paas/v4";
                case "qwen", "dashscope" -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
                default -> "https://api.openai.com/v1";
            };
        }

        // 去除末尾斜杠，确保以 /chat/completions 结尾
        base = base.replaceAll("/+$", "");
        return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
    }

    private void setCommonHeaders(HttpHeaders headers) {
        headers.set("Authorization", "Bearer " + config.getApiKey());
        headers.set("Content-Type", "application/json");
    }
}
