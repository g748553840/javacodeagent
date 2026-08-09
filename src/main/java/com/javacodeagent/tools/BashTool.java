package com.javacodeagent.tools;

import com.javacodeagent.core.enums.PermissionType;
import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.core.model.ToolExecutionResult;
import com.javacodeagent.core.tool.Tool;
import com.javacodeagent.piagent.tool.AbortSignal;
import com.javacodeagent.piagent.tool.AbortedException;
import com.javacodeagent.piagent.tool.ToolUpdateCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class BashTool implements Tool {

    private static final int DEFAULT_TIMEOUT_MS = 120_000;

    private final BackgroundTaskExecutor backgroundTaskExecutor;

    @Override
    public String getName() {
        return "Bash";
    }

    @Override
    public String getDescription() {
        return "Executes a bash command and returns its output. Supports background execution.";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> commandProp = new HashMap<>();
        commandProp.put("type", "string");
        commandProp.put("description", "The bash command to execute");
        properties.put("command", commandProp);

        Map<String, Object> timeoutProp = new HashMap<>();
        timeoutProp.put("type", "integer");
        timeoutProp.put("description", "Optional timeout in milliseconds (default: 120000)");
        properties.put("timeout", timeoutProp);

        Map<String, Object> descriptionProp = new HashMap<>();
        descriptionProp.put("type", "string");
        descriptionProp.put("description", "Clear description of what this command does");
        properties.put("description", descriptionProp);

        Map<String, Object> backgroundProp = new HashMap<>();
        backgroundProp.put("type", "boolean");
        backgroundProp.put("description", "Run in background, returns task_id immediately (default: false)");
        properties.put("run_in_background", backgroundProp);

        schema.put("properties", properties);

        List<String> required = List.of("command");
        schema.put("required", required);

        return schema;
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public PermissionType getRequiredPermission() {
        return PermissionType.SHELL_EXECUTE;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> input, ExecutionContext context) {
        return execute(input, context, AbortSignal.NEVER, null);
    }

    /**
     * 支持中止与流式输出的执行入口。
     *
     * <p>相比基础版本增加两项能力：
     * <ul>
     *   <li>收到中止信号时立即销毁子进程，而不是等命令自然结束</li>
     *   <li>逐行推送输出，让前端能实时看到长命令的进展</li>
     * </ul>
     */
    @Override
    public ToolExecutionResult execute(Map<String, Object> input,
                                       ExecutionContext context,
                                       AbortSignal signal,
                                       ToolUpdateCallback onUpdate) {
        String command = (String) input.get("command");
        if (command == null || command.isBlank()) {
            return ToolExecutionResult.error("command is required");
        }
        boolean runInBackground = input.get("run_in_background") != null
            && (Boolean) input.get("run_in_background");

        if (runInBackground) {
            String taskId = backgroundTaskExecutor.executeAsync(this, input, context);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("task_id", taskId);
            metadata.put("status", "running");
            return ToolExecutionResult.builder()
                .content("Task started: " + taskId)
                .success(true)
                .metadata(metadata)
                .build();
        }

        return executeSync(command, input, context, signal, onUpdate);
    }

    /**
     * 标记此工具包含同步阻塞操作（process.waitFor()），
     * 调用方应确保在 Schedulers.boundedElastic() 线程上执行，
     * 而非在 Netty/WebFlux IO 事件循环线程上直接调用。
     * {@link com.javacodeagent.core.tool.ToolManager} 已通过
     * {@code Mono.fromCallable} + {@code subscribeOn(boundedElastic())} 保障此点。
     */
    @Override
    public boolean isBlocking() {
        return true;
    }

    private ToolExecutionResult executeSync(String command,
                                            Map<String, Object> input,
                                            ExecutionContext context,
                                            AbortSignal signal,
                                            ToolUpdateCallback onUpdate) {
        int timeout = input.get("timeout") != null
            ? ((Number) input.get("timeout")).intValue()
            : DEFAULT_TIMEOUT_MS;

        ProcessBuilder processBuilder = new ProcessBuilder();

        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            processBuilder.command("cmd.exe", "/c", command);
        } else {
            processBuilder.command("bash", "-c", command);
        }

        if (context.getWorkingDirectory() != null) {
            processBuilder.directory(context.getWorkingDirectory().toFile());
        }

        processBuilder.redirectErrorStream(true);

        Process process = null;
        try {
            process = processBuilder.start();

            // 中止时立即销毁子进程。注册在 start() 之后、读取之前，
            // 确保中止信号在命令执行期间的任意时刻都能生效。
            final Process running = process;
            if (signal != null) {
                signal.onAbort(() -> {
                    if (running.isAlive()) {
                        log.info("Destroying bash process due to abort signal");
                        running.destroyForcibly();
                    }
                });
            }

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 每读一行检查一次中止——协作式中止的检查点
                    if (signal != null) {
                        signal.throwIfAborted();
                    }
                    output.append(line).append("\n");
                    if (onUpdate != null) {
                        onUpdate.update(Map.of("output", line));
                    }
                }
            }

            boolean finished = process.waitFor(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);

            if (!finished) {
                process.destroyForcibly();
                return ToolExecutionResult.error("Command timed out after " + timeout + "ms");
            }

            int exitCode = process.exitValue();

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("exit_code", exitCode);

            return ToolExecutionResult.builder()
                .content(output.toString())
                .success(exitCode == 0)
                .metadata(metadata)
                .build();

        } catch (AbortedException e) {
            // 中止是预期路径：进程已由 onAbort 回调销毁，这里只需回报状态
            log.info("Bash command aborted: {}", command);
            return ToolExecutionResult.error("Command aborted by user");
        } catch (Exception e) {
            // 无论异常发生在启动、读取输出还是 waitFor 阶段（包括被中断），
            // 只要进程已经启动就必须显式销毁，否则子进程会成为孤儿进程继续运行
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            log.error("Bash command failed: {}", command, e);
            return ToolExecutionResult.error("Bash command failed: " + e.getMessage());
        }
    }
}
