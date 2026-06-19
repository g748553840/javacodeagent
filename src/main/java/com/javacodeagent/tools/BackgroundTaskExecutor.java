package com.javacodeagent.tools;

import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.core.model.ToolExecutionResult;
import com.javacodeagent.core.tool.Tool;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Executes tools asynchronously and retains results for a configurable window so that
 * callers can retrieve them after the future completes.
 */
@Slf4j
@Service
public class BackgroundTaskExecutor {

    /** How long to keep a completed task result before evicting it (5 minutes). */
    private static final long RESULT_RETENTION_MS = 5 * 60 * 1000L;

    /** 专用虚拟线程执行器，避免占用 ForkJoinPool 公共池（对阻塞任务不友好）。 */
    private ExecutorService taskExecutor;

    private final Map<String, CompletableFuture<ToolExecutionResult>> activeTasks =
        new ConcurrentHashMap<>();
    private final Map<String, Long> taskStartTimes = new ConcurrentHashMap<>();

    /** Completed results retained after the future resolves. */
    private final Map<String, ToolExecutionResult> completedResults = new ConcurrentHashMap<>();
    private final Map<String, Long> completionTimes = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        taskExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @PreDestroy
    public void shutdown() {
        if (taskExecutor != null) {
            taskExecutor.shutdown();
        }
    }

    public String executeAsync(Tool tool, Map<String, Object> input, ExecutionContext context) {
        String taskId = UUID.randomUUID().toString();

        CompletableFuture<ToolExecutionResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                return tool.execute(input, context);
            } catch (Exception e) {
                log.error("Background task {} failed", taskId, e);
                return ToolExecutionResult.error("Background task failed: " + e.getMessage());
            }
        }, taskExecutor);

        activeTasks.put(taskId, future);
        taskStartTimes.put(taskId, System.currentTimeMillis());
        log.info("Started background task {} for tool {}", taskId, tool.getName());

        future.whenComplete((result, error) -> {
            activeTasks.remove(taskId);
            taskStartTimes.remove(taskId);

            ToolExecutionResult stored = (error != null)
                ? ToolExecutionResult.error("Task failed: " + error.getMessage())
                : result;

            completedResults.put(taskId, stored);
            completionTimes.put(taskId, System.currentTimeMillis());
            log.info("Background task {} completed (retained for {}s)", taskId,
                RESULT_RETENTION_MS / 1000);
        });

        return taskId;
    }

    /**
     * Blocks until the task completes or the timeout elapses.
     */
    public ToolExecutionResult getResult(String taskId, long timeoutMillis) {
        // Check completed results first
        ToolExecutionResult completed = completedResults.get(taskId);
        if (completed != null) {
            return completed;
        }

        CompletableFuture<ToolExecutionResult> future = activeTasks.get(taskId);
        if (future == null) {
            return ToolExecutionResult.error("Task not found: " + taskId);
        }

        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return ToolExecutionResult.error("Task timed out after " + timeoutMillis + "ms: " + taskId);
        } catch (Exception e) {
            return ToolExecutionResult.error("Task failed: " + e.getMessage());
        }
    }

    /**
     * Non-blocking status check.
     */
    public ToolExecutionResult checkStatus(String taskId) {
        ToolExecutionResult completed = completedResults.get(taskId);
        if (completed != null) {
            return ToolExecutionResult.builder()
                .success(completed.isSuccess())
                .content("[COMPLETED] " + completed.getContent())
                .build();
        }

        CompletableFuture<ToolExecutionResult> future = activeTasks.get(taskId);
        if (future == null) {
            return ToolExecutionResult.error("Task not found: " + taskId);
        }

        if (future.isDone()) {
            try {
                return future.get();
            } catch (Exception e) {
                return ToolExecutionResult.error("Task failed: " + e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis()
            - taskStartTimes.getOrDefault(taskId, System.currentTimeMillis());
        return ToolExecutionResult.builder()
            .success(true)
            .content("Task is running (elapsed: " + elapsed + "ms)")
            .build();
    }

    public void cancelTask(String taskId) {
        CompletableFuture<ToolExecutionResult> future = activeTasks.remove(taskId);
        if (future != null) {
            future.cancel(true);
            taskStartTimes.remove(taskId);
            log.info("Cancelled background task {}", taskId);
        }
    }

    /** Evict completed results older than RESULT_RETENTION_MS. Runs every minute. */
    @Scheduled(fixedDelay = 60_000)
    public void evictExpiredResults() {
        long cutoff = System.currentTimeMillis() - RESULT_RETENTION_MS;
        completionTimes.entrySet().removeIf(entry -> {
            if (entry.getValue() < cutoff) {
                completedResults.remove(entry.getKey());
                log.debug("Evicted expired background task result: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }
}
