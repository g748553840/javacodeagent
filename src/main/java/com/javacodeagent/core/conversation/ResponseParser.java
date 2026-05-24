package com.javacodeagent.core.conversation;

import com.javacodeagent.core.model.LLMResponse;
import com.javacodeagent.core.model.ToolCall;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 响应解析器
 * 从 LLM 响应中提取工具调用和文本内容
 */
@Slf4j
@Component
public class ResponseParser {

    /**
     * 解析 LLM 响应，提取工具调用列表和文本内容
     */
    public ParsedResponse parse(LLMResponse response) {
        if (response == null) {
            return ParsedResponse.empty();
        }

        List<ToolCall> toolCalls = response.getToolCalls() != null
            ? response.getToolCalls()
            : new ArrayList<>();

        String textContent = response.getContent() != null
            ? response.getContent()
            : "";

        return ParsedResponse.builder()
            .textContent(textContent)
            .toolCalls(toolCalls)
            .hasToolCalls(!toolCalls.isEmpty())
            .build();
    }

    /**
     * 解析后的响应
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ParsedResponse {
        private String textContent;
        private List<ToolCall> toolCalls;
        private boolean hasToolCalls;

        public static ParsedResponse empty() {
            return ParsedResponse.builder()
                .textContent("")
                .toolCalls(new ArrayList<>())
                .hasToolCalls(false)
                .build();
        }
    }
}
