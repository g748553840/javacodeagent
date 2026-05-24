package com.javacodeagent.core.agent;

import java.util.List;

public interface Agent {
    String getType();

    String getDescription();

    List<String> getAvailableTools();

    AgentResult process(AgentTask task, AgentContext context);
}
