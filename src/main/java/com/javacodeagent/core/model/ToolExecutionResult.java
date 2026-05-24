package com.javacodeagent.core.model;

import com.javacodeagent.core.enums.ToolStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionResult {
    private String content;
    private boolean success;
    private String error;
    private Map<String, Object> metadata;
    private ToolStatus status;

    public static ToolExecutionResult success(String content) {
        return ToolExecutionResult.builder()
            .content(content)
            .success(true)
            .status(ToolStatus.COMPLETED)
            .build();
    }

    public static ToolExecutionResult error(String error) {
        return ToolExecutionResult.builder()
            .error(error)
            .success(false)
            .status(ToolStatus.FAILED)
            .build();
    }
}