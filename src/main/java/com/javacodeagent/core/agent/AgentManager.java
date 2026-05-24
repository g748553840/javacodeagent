package com.javacodeagent.core.agent;

import com.javacodeagent.core.enums.PermissionLevel;
import com.javacodeagent.core.model.ExecutionContext;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentManager {

    private final Map<String, Agent> agents = new ConcurrentHashMap<>();
    private final List<Agent> agentBeans;

    @PostConstruct
    public void init() {
        for (Agent agent : agentBeans) {
            registerAgent(agent);
        }
    }

    public void registerAgent(Agent agent) {
        agents.put(agent.getType(), agent);
        log.info("Registered agent: {}", agent.getType());
    }

    public AgentResult executeAgent(String agentType, AgentTask task, AgentContext context) {
        Agent agent = agents.get(agentType);
        if (agent == null) {
            return AgentResult.builder()
                .success(false)
                .error("Unknown agent type: " + agentType)
                .build();
        }

        try {
            log.info("Executing agent: {} with task: {}", agentType, task.getDescription());
            return agent.process(task, context);
        } catch (Exception e) {
            log.error("Agent execution failed: {}", agentType, e);
            return AgentResult.builder()
                .success(false)
                .error("Agent execution failed: " + e.getMessage())
                .build();
        }
    }

    public CompletableFuture<AgentResult> launchAgentAsync(String agentType, AgentTask task, AgentContext context) {
        return CompletableFuture.supplyAsync(() -> executeAgent(agentType, task, context));
    }

    /**
     * 并行启动多个 Agent，等待所有完成并返回结果列表
     */
    public List<AgentResult> launchParallelAgents(List<AgentTask> tasks, AgentContext context) {
        List<CompletableFuture<AgentResult>> futures = new ArrayList<>();
        for (AgentTask task : tasks) {
            CompletableFuture<AgentResult> future = CompletableFuture.supplyAsync(() ->
                executeAgent(task.getType(), task, context));
            futures.add(future);
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return futures.stream()
            .map(CompletableFuture::join)
            .toList();
    }

    /**
     * 在隔离环境中执行 Agent（READ_ONLY 权限，独立上下文）
     */
    public CompletableFuture<AgentResult> launchIsolatedAgent(AgentTask task, AgentContext context) {
        ExecutionContext isolatedExec = ExecutionContext.builder()
            .userId(context.getExecutionContext() != null
                ? context.getExecutionContext().getUserId() : "system")
            .permissionLevel(PermissionLevel.READ_ONLY)
            .workingDirectory(context.getExecutionContext() != null
                ? context.getExecutionContext().getWorkingDirectory() : null)
            .build();

        AgentContext isolatedContext = AgentContext.builder()
            .agentId(UUID.randomUUID().toString())
            .parentAgentId(context.getAgentId())
            .executionContext(isolatedExec)
            .availableTools(List.of("glob", "grep", "read", "list"))
            .build();

        return CompletableFuture.supplyAsync(() -> executeAgent(task.getType(), task, isolatedContext));
    }
}
