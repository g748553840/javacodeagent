package com.javacodeagent.tools;

import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.core.model.ToolExecutionResult;
import com.javacodeagent.core.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class BackgroundTaskExecutor {

    private final Map<String, CompletableFuture<ToolExecutionResult>> tasks = new ConcurrentHashMap<>();
    private final Map<String, Long> taskStartTimes = new ConcurrentHashMap<>();

    /**
     * 异步执行工具调用
     */
    public String executeAsync(Tool tool, Map<String, Object> input, ExecutionContext context) {
        String taskId = UUID.randomUUID().toString();
        CompletableFuture<ToolExecutionResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                return tool.execute(input, context);
            } catch (Exception e) {
                log.error("Background task {} failed", taskId, e);
                return ToolExecutionResult.error("Background task failed: " + e.getMessage());
            }
        });

        tasks.put(taskId, future);
        taskStartTimes.put(taskId, System.currentTimeMillis());
        log.info("Started background task: {} for tool: {}", taskId, tool.getName());

        // 自动清理已完成的任务
        future.whenComplete((result, error) -> {
            tasks.remove(taskId);
            taskStartTimes.remove(taskId);
            log.info("Background task {} completed", taskId);
        });

        return taskId;
    }

    /**
     * 获取任务结果（同步等待）
     */
    public ToolExecutionResult getResult(String taskId, long timeoutMillis) {
        CompletableFuture<ToolExecutionResult> future = tasks.get(taskId);
        if (future == null) {
            return ToolExecutionResult.error("Task not found: " + taskId);
        }

        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return ToolExecutionResult.error("Task timed out: " + taskId);
        } catch (Exception e) {
            return ToolExecutionResult.error("Task failed: " + e.getMessage());
        }
    }

    /**
     * 非阻塞检查任务状态
     */
    public ToolExecutionResult checkStatus(String taskId) {
        CompletableFuture<ToolExecutionResult> future = tasks.get(taskId);
        if (future == null) {
            return ToolExecutionResult.builder()
                .success(false)
                .content("Task not found: " + taskId)
                .build();
        }

        if (!future.isDone()) {
            long elapsed = System.currentTimeMillis() - taskStartTimes.getOrDefault(taskId, System.currentTimeMillis());
            return ToolExecutionResult.builder()
                .success(true)
                .content("Task is still running (elapsed: " + elapsed + "ms)")
                .build();
        }

        try {
            return future.get();
        } catch (Exception e) {
            return ToolExecutionResult.error("Task failed: " + e.getMessage());
        }
    }

    /**
     * 停止任务
     */
    public void cancelTask(String taskId) {
        CompletableFuture<ToolExecutionResult> future = tasks.get(taskId);
        if (future != null) {
            future.cancel(true);
            tasks.remove(taskId);
            taskStartTimes.remove(taskId);
            log.info("Cancelled background task: {}", taskId);
        }
    }
}
