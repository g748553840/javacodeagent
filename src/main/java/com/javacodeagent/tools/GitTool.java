package com.javacodeagent.tools;

import com.javacodeagent.core.enums.PermissionType;
import com.javacodeagent.core.hook.HookContext;
import com.javacodeagent.core.hook.HookManager;
import com.javacodeagent.core.hook.HookResult;
import com.javacodeagent.core.hook.HookType;
import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.core.model.ToolExecutionResult;
import com.javacodeagent.core.tool.Tool;
import com.javacodeagent.piagent.tool.ToolExecutionMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Git operations tool. Supports read-only queries (status, diff, log, branch)
 * and state-changing operations (add, commit, checkout, push, pull).
 *
 * The allowed commands whitelist prevents arbitrary shell injection.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitTool implements Tool {

    private final HookManager hookManager;

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private static final Set<String> ALLOWED_COMMANDS = Set.of(
        "status", "diff", "log", "show", "branch", "remote",
        "add", "commit", "checkout", "switch", "pull", "push",
        "fetch", "merge", "rebase", "stash", "tag", "init", "clone"
    );

    @Override
    public String getName() {
        return "Git";
    }

    @Override
    public String getDescription() {
        return "Execute git operations in the working directory. " +
               "Supports: status, diff, log, branch, add, commit, checkout, pull, push, fetch, stash.";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> commandProp = new HashMap<>();
        commandProp.put("type", "string");
        commandProp.put("description",
            "Git subcommand to run (e.g. status, diff, log, add, commit, checkout, pull, push)");
        commandProp.put("enum", List.copyOf(ALLOWED_COMMANDS));
        properties.put("command", commandProp);

        Map<String, Object> argsProp = new HashMap<>();
        argsProp.put("type", "string");
        argsProp.put("description",
            "Additional arguments for the git command, e.g. '--oneline -10' for log, " +
            "'-m \"commit message\"' for commit, or a file path for add");
        properties.put("args", argsProp);

        schema.put("properties", properties);
        schema.put("required", List.of("command"));
        return schema;
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public PermissionType getRequiredPermission() {
        return PermissionType.GIT_OPERATION;
    }

    /**
     * git 操作串行执行。
     *
     * <p>git 的索引（{@code .git/index}）是单一共享状态，还有 {@code index.lock}
     * 这个显式互斥文件。并行跑 {@code add} 和 {@code commit} 时，后者可能在前者
     * 完成暂存前就提交，得到一个内容不完整的 commit；两个写操作同时进行则会有一个
     * 直接因拿不到 lock 而失败。即便是只读的 {@code status}，与并发的 {@code add}
     * 交错时读到的也是中间态。区分读写命令再决定模式是可以做的，但收益很小——
     * git 命令本身通常在百毫秒级，省下的并行时间不值得引入这种细粒度判断。
     */
    @Override
    public ToolExecutionMode getExecutionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> input, ExecutionContext context) {
        String command = (String) input.get("command");
        String args = input.get("args") instanceof String s ? s.trim() : "";

        if (command == null || command.isBlank()) {
            return ToolExecutionResult.error("Git command is required");
        }

        String normalised = command.trim().toLowerCase();
        if (!ALLOWED_COMMANDS.contains(normalised)) {
            return ToolExecutionResult.error(
                "Git command '" + command + "' is not allowed. Allowed: " + ALLOWED_COMMANDS);
        }

        // PRE_COMMIT hook — 仅对 commit 命令触发，可拦截
        if ("commit".equals(normalised)) {
            HookResult preResult = hookManager.triggerHook(HookType.PRE_COMMIT, HookContext.builder()
                .type(HookType.PRE_COMMIT)
                .userId(context.getUserId())
                .conversationId(context.getConversationId())
                .data(Map.of("command", normalised, "args", args))
                .build());
            if (!preResult.shouldContinue()) {
                return ToolExecutionResult.error("Commit blocked by pre-commit hook: " + preResult.getMessage());
            }
        }

        ToolExecutionResult result = runGit(normalised, args, context.getWorkingDirectory());

        // POST_COMMIT hook — 仅对成功的 commit 触发（通知型）
        if ("commit".equals(normalised) && result.isSuccess()) {
            hookManager.triggerHook(HookType.POST_COMMIT, HookContext.builder()
                .type(HookType.POST_COMMIT)
                .userId(context.getUserId())
                .conversationId(context.getConversationId())
                .data(Map.of("command", normalised, "args", args, "output", result.getContent() != null ? result.getContent() : ""))
                .build());
        }

        return result;
    }

    private ToolExecutionResult runGit(String command, String args, Path workingDir) {
        Process process = null;
        try {
            List<String> cmdLine = buildCommandLine(command, args);
            log.info("Running git command: {}", cmdLine);
            ProcessBuilder pb = new ProcessBuilder(cmdLine);
            pb.redirectErrorStream(true);

            if (workingDir != null) {
                pb.directory(workingDir.toFile());
            }

            process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolExecutionResult.error(
                    "Git command timed out after " + DEFAULT_TIMEOUT_SECONDS + "s");
            }

            int exitCode = process.exitValue();
            String result = output.toString().trim();

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("exit_code", exitCode);
            metadata.put("command", "git " + command + (args.isEmpty() ? "" : " " + args));

            if (exitCode == 0) {
                return ToolExecutionResult.builder()
                    .success(true)
                    .content(result.isEmpty() ? "(no output)" : result)
                    .metadata(metadata)
                    .build();
            } else {
                return ToolExecutionResult.builder()
                    .success(false)
                    .content(result)
                    .error("git exited with code " + exitCode)
                    .metadata(metadata)
                    .build();
            }
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.error("Invalid git args: " + e.getMessage());
        } catch (Exception e) {
            // 无论异常发生在启动、读取输出还是 waitFor 阶段（包括被中断），
            // 只要进程已经启动就必须显式销毁，否则子进程会成为孤儿进程继续运行
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            log.error("Git command failed: git {} {}", command, args, e);
            return ToolExecutionResult.error("Git execution error: " + e.getMessage());
        }
    }

    /**
     * Builds the command list. Args string is split carefully to avoid shell injection —
     * we never pass args through a shell interpreter.
     */
    private List<String> buildCommandLine(String command, String args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add(command);

        if (!args.isEmpty()) {
            // Simple tokenise: split on whitespace, but respect quoted strings
            cmd.addAll(tokenise(args));
        }
        return cmd;
    }

    private List<String> tokenise(String args) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        char quoteChar = 0;

        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (inQuote) {
                if (c == quoteChar) {
                    inQuote = false;
                    tokens.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                quoteChar = c;
            } else if (Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        if (inQuote) {
            throw new IllegalArgumentException("Unclosed quote in git args: " + args);
        }
        return tokens;
    }
}
