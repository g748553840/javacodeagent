package com.javacodeagent.core.llm;

import com.javacodeagent.config.LLMConfig;
import com.javacodeagent.core.model.ConversationContext;
import com.javacodeagent.core.model.LLMResponse;
import com.javacodeagent.core.model.Message;
import com.javacodeagent.core.model.ToolCall;
import com.javacodeagent.core.model.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnthropicLLMClient implements LLMClient {

    private final WebClient webClient;
    private final LLMConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<LLMResponse> chat(ConversationContext context) {
        Map<String, Object> requestBody = buildRequestBody(context);

        return webClient.post()
            .uri(config.getEndpoint() + "/v1/messages")
            .headers(headers -> {
                headers.set("x-api-key", config.getApiKey());
                headers.set("anthropic-version", "2023-06-01");
                headers.set("content-type", "application/json");
            })
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(String.class)
            .map(this::parseResponse)
            .onErrorResume(e -> {
                log.error("LLM request failed", e);
                return Mono.just(LLMResponse.builder()
                    .content("Error: " + e.getMessage())
                    .build());
            });
    }

    @Override
    public Flux<String> chatStream(ConversationContext context) {
        Map<String, Object> requestBody = buildRequestBody(context);

        return webClient.post()
            .uri(config.getEndpoint() + "/v1/messages")
            .headers(headers -> {
                headers.set("x-api-key", config.getApiKey());
                headers.set("anthropic-version", "2023-06-01");
                headers.set("content-type", "application/json");
            })
            .bodyValue(requestBody)
            .retrieve()
            .bodyToFlux(String.class)
            .map(this::parseStreamEvent);
    }

    private Map<String, Object> buildRequestBody(ConversationContext context) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", config.getModel());
        body.put("max_tokens", config.getMaxTokens());
        body.put("messages", convertMessages(context.getMessages()));

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
                    // Anthropic API: tool results use role "user" with tool_result content blocks
                    msgMap.put("role", "user");

                    List<Map<String, Object>> contentArray = new ArrayList<>();
                    Map<String, Object> toolResultBlock = new HashMap<>();
                    toolResultBlock.put("type", "tool_result");
                    toolResultBlock.put("tool_use_id", msg.getToolCallId());
                    toolResultBlock.put("content", msg.getContent() != null ? msg.getContent() : "");
                    contentArray.add(toolResultBlock);

                    msgMap.put("content", contentArray);
                }
                case ASSISTANT -> {
                    msgMap.put("role", "assistant");

                    List<Map<String, Object>> contentArray = new ArrayList<>();

                    // Add text content if present
                    if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                        Map<String, Object> textBlock = new HashMap<>();
                        textBlock.put("type", "text");
                        textBlock.put("text", msg.getContent());
                        contentArray.add(textBlock);
                    }

                    // Add tool_use blocks for each tool call
                    if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                        for (ToolCall tc : msg.getToolCalls()) {
                            Map<String, Object> toolUseBlock = new HashMap<>();
                            toolUseBlock.put("type", "tool_use");
                            toolUseBlock.put("id", tc.getId());
                            toolUseBlock.put("name", tc.getName());
                            toolUseBlock.put("input", tc.getInput());
                            contentArray.add(toolUseBlock);
                        }
                    }

                    msgMap.put("content", contentArray);
                }
                default -> {
                    msgMap.put("role", msg.getType().name().toLowerCase());
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
            Map<String, Object> toolMap = new HashMap<>();
            toolMap.put("name", tool.getName());
            toolMap.put("description", tool.getDescription());
            toolMap.put("input_schema", tool.getInputSchema());
            result.add(toolMap);
        }
        return result;
    }

    private LLMResponse parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode contentNode = root.path("content");

            StringBuilder content = new StringBuilder();
            List<ToolCall> toolCalls = new ArrayList<>();

            if (contentNode.isArray()) {
                for (JsonNode node : contentNode) {
                    String type = node.path("type").asText("");
                    if ("text".equals(type)) {
                        content.append(node.get("text").asText());
                    } else if ("tool_use".equals(type)) {
                        String toolName = node.path("name").asText();
                        String toolId = node.path("id").asText();
                        JsonNode inputNode = node.path("input");

                        Map<String, Object> input = objectMapper.convertValue(
                            inputNode, Map.class);

                        toolCalls.add(ToolCall.builder()
                            .name(toolName)
                            .id(toolId)
                            .input(input)
                            .build());
                    }
                }
            }

            return LLMResponse.builder()
                .id(root.path("id").asText())
                .model(root.path("model").asText())
                .content(content.toString())
                .toolCalls(toolCalls.isEmpty() ? null : toolCalls)
                .stopReason(root.path("stop_reason").asText(null))
                .build();
        } catch (Exception e) {
            log.error("Failed to parse LLM response", e);
            return LLMResponse.builder()
                .content("Error parsing response: " + e.getMessage())
                .build();
        }
    }

    private String parseStreamEvent(String event) {
        try {
            if (event.startsWith("data: ")) {
                String data = event.substring(6);
                if (data.equals("[DONE]")) {
                    return "";
                }
                JsonNode root = objectMapper.readTree(data);
                JsonNode delta = root.path("delta");
                if (delta.has("text")) {
                    return delta.get("text").asText();
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse stream event", e);
        }
        return "";
    }
}