package com.javacodeagent.core.model;

import com.javacodeagent.core.enums.MessageType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message {
    private MessageType type;
    private String content;
    private List<ToolCall> toolCalls;
    private String toolCallId;
}