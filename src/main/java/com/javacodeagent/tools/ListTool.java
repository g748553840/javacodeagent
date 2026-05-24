package com.javacodeagent.tools;

import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.core.model.ToolExecutionResult;
import com.javacodeagent.core.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class ListTool implements Tool {

    private final FilePathResolver pathResolver;

    @Override
    public String getName() {
        return "List";
    }

    @Override
    public String getDescription() {
        return "List files and directories in a path";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> pathProp = new HashMap<>();
        pathProp.put("type", "string");
        pathProp.put("description", "The path to list (default: current directory)");
        properties.put("path", pathProp);

        schema.put("properties", properties);

        return schema;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> input, ExecutionContext context) {
        try {
            Path path;
            if (input.get("path") != null) {
                path = pathResolver.resolve((String) input.get("path"), context.getWorkingDirectory());
            } else if (context.getWorkingDirectory() != null) {
                path = context.getWorkingDirectory();
            } else {
                path = pathResolver.resolve(".", null);
            }

            if (!Files.exists(path)) {
                return ToolExecutionResult.error("Path not found: " + path);
            }

            if (Files.isRegularFile(path)) {
                return ToolExecutionResult.success(getFileInfo(path));
            }

            StringBuilder result = new StringBuilder();
            result.append("Listing: ").append(path).append("\n\n");

            try (Stream<Path> paths = Files.list(path)) {
                paths.sorted()
                    .forEach(p -> {
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                            String type = attrs.isDirectory() ? "[DIR] " : "[FILE]";
                            String size = attrs.isRegularFile()
                                ? formatFileSize(attrs.size())
                                : "";
                            result.append(type)
                                .append(String.format("%-10s", size))
                                .append(" ")
                                .append(p.getFileName())
                                .append("\n");
                        } catch (Exception e) {
                            log.error("Error reading attributes for: {}", p, e);
                        }
                    });
            }

            return ToolExecutionResult.success(result.toString());

        } catch (SecurityException e) {
            log.warn("Path traversal attempt in list");
            return ToolExecutionResult.error("Security error: " + e.getMessage());
        } catch (Exception e) {
            log.error("List failed for path", e);
            return ToolExecutionResult.error("List failed: " + e.getMessage());
        }
    }

    private String getFileInfo(Path path) throws Exception {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        StringBuilder info = new StringBuilder();
        info.append("File: ").append(path).append("\n");
        info.append("Size: ").append(formatFileSize(attrs.size())).append("\n");
        info.append("Created: ").append(attrs.creationTime()).append("\n");
        info.append("Modified: ").append(attrs.lastModifiedTime()).append("\n");
        info.append("Is Directory: ").append(attrs.isDirectory()).append("\n");
        return info.toString();
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
