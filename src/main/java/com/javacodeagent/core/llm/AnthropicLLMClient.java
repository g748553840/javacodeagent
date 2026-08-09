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
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Claude API 实现。
 * 不直接注册为 Spring Bean，由 LLMClientConfig 工厂按 llm.provider=anthropic 创建。
 */
@Slf4j
public class AnthropicLLMClient implements LLMClient {

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String ANTHROPIC_BETA_THINKING = "interleaved-thinking-2025-05-14";
    private static final int SSE_DATA_PREFIX_LENGTH = 6; // "data: ".length()

    private final WebClient webClient;
    private final LLMConfig config;
    private final ObjectMapper objectMapper;

    public AnthropicLLMClient(WebClient webClient, LLMConfig config, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<LLMResponse> chat(ConversationContext context) {
        Map<String, Object> requestBody = buildRequestBody(context, false);

        return webClient.post()
            .uri(config.getEndpoint() + "/v1/messages")
            .headers(this::setCommonHeaders)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(String.class)
            .map(this::parseResponse)
            .onErrorResume(e -> {
                // 标记为显式失败而非伪装成正常响应。
                // 上游的重试装饰器依赖 error 标记与 errorMessage 做分类判定；
                // 若继续把异常转成 content="Error: ..." 的成功响应，
                // 重试层无法感知失败，错误也会被当作模型输出持久化进对话历史。
                log.error("Anthropic LLM request failed", e);
                return Mono.just(LLMResponse.ofError(describeError(e)));
            });
    }

    @Override
    public Flux<String> chatStream(ConversationContext context) {
        Map<String, Object> requestBody = buildRequestBody(context, true);

        return webClient.post()
            .uri(config.getEndpoint() + "/v1/messages")
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

    private void setCommonHeaders(HttpHeaders headers) {
        headers.set("x-api-key", config.getApiKey());
        headers.set("anthropic-version", ANTHROPIC_VERSION);
        headers.set("content-type", "application/json");
        if (config.isThinkingEnabled()) {
            headers.set("anthropic-beta", ANTHROPIC_BETA_THINKING);
        }
    }

    /**
     * 构造供重试分类器判定的错误文本。
     *
     * <p>关键点是把 HTTP 状态码显式拼进文本——WebClient 的
     * {@code WebClientResponseException.getMessage()} 形如 "429 Too Many Requests"
     * 通常已含状态码，但部分实现只给短语；显式拼接可确保
     * {@code RetryableErrorClassifier} 的 {@code \b(429|500|502|503|504|524)\b}
     * 模式能稳定匹配。响应体也一并附上，因为 provider 的
     * "insufficient_quota" 之类判定信息只存在于 body 中。
     */
    private String describeError(Throwable e) {
        if (e instanceof WebClientResponseException wcre) {
            String body = wcre.getResponseBodyAsString();
            String base = wcre.getStatusCode().value() + " " + wcre.getStatusText();
            return (body == null || body.isBlank()) ? base : base + " | " + body;
        }
        String msg = e.getMessage();
        return msg != null ? msg : e.getClass().getSimpleName();
    }

    private Map<String, Object> buildRequestBody(ConversationContext context, boolean stream) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", config.getModel());
        body.put("max_tokens", config.getMaxTokens());
        body.put("messages", convertMessages(context.getMessages()));

        if (stream) {
            body.put("stream", true);
        }
        if (config.getSystemPrompt() != null && !config.getSystemPrompt().isEmpty()) {
            body.put("system", config.getSystemPrompt());
        }
        if (config.isThinkingEnabled()) {
            int budget = config.getThinkingBudgetTokens() > 0
                ? config.getThinkingBudgetTokens()
                : LLMConfig.DEFAULT_THINKING_BUDGET_TOKENS;
            body.put("thinking", Map.of("type", "enabled", "budget_tokens", budget));
        }
        if (context.getAvailableTools() != null && !context.getAvailableTools().isEmpty()) {
            body.put("tools", convertTools(context.getAvailableTools()));
        }
        return body;
    }

    private List<Map<String, Object>> convertMessages(List<Message> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Message msg : messages) {
            Map<String, Object> msgMap = new HashMap<>();
            switch (msg.getType()) {
                case TOOL_RESULT -> {
                    msgMap.put("role", "user");
                    Map<String, Object> block = new HashMap<>();
                    block.put("type", "tool_result");
                    block.put("tool_use_id", msg.getToolCallId());
                    block.put("content", msg.getContent() != null ? msg.getContent() : "");
                    msgMap.put("content", List.of(block));
                }
                case ASSISTANT -> {
                    msgMap.put("role", "assistant");
                    List<Map<String, Object>> content = new ArrayList<>();
                    if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                        content.add(Map.of("type", "text", "text", msg.getContent()));
                    }
                    if (msg.getToolCalls() != null) {
                        for (ToolCall tc : msg.getToolCalls()) {
                            Map<String, Object> b = new HashMap<>();
                            b.put("type", "tool_use");
                            b.put("id", tc.getId());
                            b.put("name", tc.getName());
                            b.put("input", tc.getInput());
                            content.add(b);
                        }
                    }
                    msgMap.put("content", content);
                }
                case SYSTEM -> {
                    msgMap.put("role", "user");
                    msgMap.put("content", "[System context]: " + msg.getContent());
                }
                default -> {
                    msgMap.put("role", "user");
                    msgMap.put("content", msg.getContent() != null ? msg.getContent() : "");
                }
            }
            result.add(msgMap);
        }
        return result;
    }

    private List<Map<String, Object>> convertTools(List<ToolDefinition> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ToolDefinition tool : tools) {
            result.add(Map.of(
                "name", tool.getName(),
                "description", tool.getDescription(),
                "input_schema", tool.getInputSchema()
            ));
        }
        return result;
    }

    private LLMResponse parseResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.has("error")) {
                String msg = root.path("error").path("message").asText("Unknown API error");
                log.error("Anthropic API error: {}", msg);
                return LLMResponse.builder().content("API Error: " + msg).build();
            }

            StringBuilder text = new StringBuilder();
            List<ToolCall> toolCalls = new ArrayList<>();

            for (JsonNode node : root.path("content")) {
                String type = node.path("type").asText("");
                switch (type) {
                    case "text" -> text.append(node.path("text").asText());
                    case "tool_use" -> toolCalls.add(ToolCall.builder()
                        .id(node.path("id").asText())
                        .name(node.path("name").asText())
                        .input(objectMapper.convertValue(node.path("input"),
                            new TypeReference<Map<String, Object>>() {}))
                        .build());
                }
            }

            return LLMResponse.builder()
                .id(root.path("id").asText())
                .model(root.path("model").asText())
                .content(text.toString())
                .toolCalls(toolCalls.isEmpty() ? null : toolCalls)
                .stopReason(root.path("stop_reason").asText(null))
                .build();
        } catch (Exception e) {
            log.error("Failed to parse Anthropic response: {}", body, e);
            return LLMResponse.builder().content("Parse error: " + e.getMessage()).build();
        }
    }

    /**
     * 结构化流式：逐 Token 发出文本块，遇到工具调用时组装完整后发出，
     * 最终发出 DONE 信号。
     *
     * Anthropic SSE 事件序列（工具调用场景）：
     *   content_block_start  index=0  type=text
     *   content_block_delta  index=0  text_delta
     *   content_block_stop   index=0
     *   content_block_start  index=1  type=tool_use  id=toolu_01  name=Read
     *   content_block_delta  index=1  input_json_delta  {"file_path":
     *   content_block_delta  index=1  input_json_delta  "/src/Main.java"}
     *   content_block_stop   index=1
     *   message_delta        stop_reason=tool_use
     *   message_stop
     */
    @Override
    public Flux<LLMStreamChunk> chatStreamFull(ConversationContext context) {
        Map<String, Object> requestBody = buildRequestBody(context, true);

        // concatMap 保证串行处理，无需 ConcurrentHashMap
        Map<Integer, BlockState> blocks = new HashMap<>();

        return webClient.post()
            .uri(config.getEndpoint() + "/v1/messages")
            .headers(headers -> {
                setCommonHeaders(headers);
                headers.set("Accept", "text/event-stream");
            })
            .bodyValue(requestBody)
            .retrieve()
            .bodyToFlux(String.class)
            .concatMap(line -> parseStreamChunk(line, blocks))
            .onErrorResume(e -> {
                log.error("Anthropic stream failed", e);
                return Flux.just(LLMStreamChunk.error(e.getMessage()));
            });
    }

    /**
     * 将单行 SSE 数据解析为 0-N 个 LLMStreamChunk。
     */
    private Flux<LLMStreamChunk> parseStreamChunk(String line, Map<Integer, BlockState> blocks) {
        if (line == null || !line.startsWith("data: ")) return Flux.empty();
        try {
            String data = line.substring(SSE_DATA_PREFIX_LENGTH).trim();
            if ("[DONE]".equals(data)) return Flux.empty();

            JsonNode root = objectMapper.readTree(data);
            String type = root.path("type").asText("");

            return switch (type) {
                case "content_block_start" -> {
                    int index = root.path("index").asInt();
                    JsonNode block = root.path("content_block");
                    String blockType = block.path("type").asText("");
                    if ("tool_use".equals(blockType)) {
                        BlockState state = new BlockState();
                        state.toolId = block.path("id").asText();
                        state.toolName = block.path("name").asText();
                        state.isToolUse = true;
                        blocks.put(index, state);
                    } else {
                        blocks.put(index, new BlockState());
                    }
                    yield Flux.empty();
                }

                case "content_block_delta" -> {
                    int index = root.path("index").asInt();
                    JsonNode delta = root.path("delta");
                    String deltaType = delta.path("type").asText("");
                    BlockState state = blocks.getOrDefault(index, new BlockState());

                    if ("text_delta".equals(deltaType)) {
                        String text = delta.path("text").asText("");
                        state.textAccum.append(text);
                        yield Flux.just(LLMStreamChunk.text(text));
                    } else if ("input_json_delta".equals(deltaType)) {
                        state.inputJsonAccum.append(delta.path("partial_json").asText(""));
                        yield Flux.empty();
                    }
                    yield Flux.empty();
                }

                case "content_block_stop" -> {
                    int index = root.path("index").asInt();
                    BlockState state = blocks.remove(index);
                    if (state != null && state.isToolUse) {
                        // 组装完整工具调用
                        Map<String, Object> input;
                        try {
                            input = objectMapper.readValue(state.inputJsonAccum.toString(),
                                new TypeReference<Map<String, Object>>() {});
                        } catch (Exception e) {
                            input = Map.of("_raw", state.inputJsonAccum.toString());
                        }
                        ToolCall tc = ToolCall.builder()
                            .id(state.toolId)
                            .name(state.toolName)
                            .input(input)
                            .build();
                        yield Flux.just(LLMStreamChunk.toolCall(tc));
                    }
                    yield Flux.empty();
                }

                case "message_delta" -> {
                    String stopReason = root.path("delta").path("stop_reason").asText(null);
                    yield Flux.just(LLMStreamChunk.done(stopReason));
                }

                default -> Flux.empty();
            };
        } catch (Exception e) {
            log.debug("Skipping unparseable Anthropic SSE line: {}", line);
            return Flux.empty();
        }
    }

    /** 内部：记录单个 content block 的积累状态 */
    private static class BlockState {
        boolean isToolUse = false;
        String toolId = "";
        String toolName = "";
        StringBuilder textAccum = new StringBuilder();
        StringBuilder inputJsonAccum = new StringBuilder();
    }

    /**
     * 原始文本流（chatStream 接口实现）。
     * Anthropic SSE delta 格式：
     *   data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"..."}}
     */
    private String parseStreamEvent(String line) {
        if (line == null || !line.startsWith("data: ")) return "";
        try {
            String data = line.substring(SSE_DATA_PREFIX_LENGTH).trim();
            if ("[DONE]".equals(data)) return "";
            JsonNode root = objectMapper.readTree(data);
            if ("content_block_delta".equals(root.path("type").asText())) {
                JsonNode delta = root.path("delta");
                if ("text_delta".equals(delta.path("type").asText())) {
                    return delta.path("text").asText("");
                }
            }
        } catch (Exception e) {
            log.debug("Skipping unparseable Anthropic SSE line: {}", line);
        }
        return "";
    }
}
