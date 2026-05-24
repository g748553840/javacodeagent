package com.javacodeagent.core.agent;

import com.javacodeagent.core.model.ExecutionContext;
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
public class AgentContext {
    private String agentId;
    private String parentAgentId;
    private ExecutionContext executionContext;
    private List<String> availableTools;
    private Map<String, Object> metadata;
}
