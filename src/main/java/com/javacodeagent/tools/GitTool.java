package com.javacodeagent.tools;

import com.javacodeagent.core.enums.PermissionType;
import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.core.model.ToolExecutionResult;
import com.javacodeagent.core.tool.Tool;
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
public class GitTool implements Tool {

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

        return runGit(normalised, args, context.getWorkingDirectory());
    }

    private ToolExecutionResult runGit(String command, String args, Path workingDir) {
        try {
            List<String> cmdLine = buildCommandLine(command, args);
            log.info("Running git command: {}", cmdLine);
            ProcessBuilder pb = new ProcessBuilder(cmdLine);
            pb.redirectErrorStream(true);

            if (workingDir != null) {
                pb.directory(workingDir.toFile());
            }

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
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
