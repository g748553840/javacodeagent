package com.javacodeagent.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LLMResponse {
    private String id;
    private String type;
    private String role;
    private List<Message> messages;
    private String content;
    private List<ToolCall> toolCalls;
    private String stopReason;
    private Map<String, Object> usage;
    private String model;
}