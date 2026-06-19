package com.javacodeagent.core.agent;

import com.javacodeagent.core.enums.PermissionLevel;
import com.javacodeagent.core.model.ExecutionContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentManager {

    private final Map<String, Agent> agents = new ConcurrentHashMap<>();
    private final List<Agent> agentBeans;

    /**
     * 专用线程池，避免使用 ForkJoinPool 公共池（ForkJoinPool 对阻塞任务不友好，
     * 且与 WebFlux 公共线程池共争资源）。
     * 使用虚拟线程（Java 21+）：轻量、无需调整线程数、阻塞友好。
     */
    private ExecutorService agentExecutor;

    @PostConstruct
    public void init() {
        // Java 21 虚拟线程执行器：每个任务一个虚拟线程，阻塞时不占用平台线程
        agentExecutor = Executors.newVirtualThreadPerTaskExecutor();
        for (Agent agent : agentBeans) {
            registerAgent(agent);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (agentExecutor != null) {
            agentExecutor.shutdown();
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
        return CompletableFuture.supplyAsync(
            () -> executeAgent(agentType, task, context),
            agentExecutor);
    }

    /**
     * 并行启动多个 Agent，等待所有完成并返回结果列表。
     * 使用专用虚拟线程执行器，避免阻塞 WebFlux 事件循环或公共 ForkJoinPool。
     */
    public List<AgentResult> launchParallelAgents(List<AgentTask> tasks, AgentContext context) {
        List<CompletableFuture<AgentResult>> futures = new ArrayList<>();
        for (AgentTask task : tasks) {
            CompletableFuture<AgentResult> future = CompletableFuture.supplyAsync(
                () -> executeAgent(task.getType(), task, context),
                agentExecutor);
            futures.add(future);
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return futures.stream()
            .map(CompletableFuture::join)
            .toList();
    }

    /**
     * 在隔离环境中执行 Agent（READ_ONLY 权限，独立上下文）。
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

        return CompletableFuture.supplyAsync(
            () -> executeAgent(task.getType(), task, isolatedContext),
            agentExecutor);
    }
}

