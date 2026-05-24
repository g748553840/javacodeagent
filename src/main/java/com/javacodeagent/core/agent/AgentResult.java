package com.javacodeagent.core.agent;

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
public class AgentResult {
    private String output;
    private boolean success;
    private String error;
    private List<AgentResult> subResults;
    private Map<String, Object> metadata;
}
