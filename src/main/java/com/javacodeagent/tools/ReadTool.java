package com.javacodeagent.tools;

import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.core.model.ToolExecutionResult;
import com.javacodeagent.core.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReadTool implements Tool {

    private final FilePathResolver pathResolver;

    @Override
    public String getName() {
        return "Read";
    }

    @Override
    public String getDescription() {
        return "Read the contents of a file";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> filePathProp = new HashMap<>();
        filePathProp.put("type", "string");
        filePathProp.put("description", "The absolute path to the file to read");
        properties.put("file_path", filePathProp);

        Map<String, Object> limitProp = new HashMap<>();
        limitProp.put("type", "integer");
        limitProp.put("description", "Optional line limit");
        properties.put("limit", limitProp);

        Map<String, Object> offsetProp = new HashMap<>();
        offsetProp.put("type", "integer");
        offsetProp.put("description", "Optional line offset");
        properties.put("offset", offsetProp);

        schema.put("properties", properties);

        List<String> required = List.of("file_path");
        schema.put("required", required);

        return schema;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> input, ExecutionContext context) {
        String filePath = (String) input.get("file_path");

        try {
            Path path = pathResolver.resolve(filePath, context.getWorkingDirectory());

            if (!Files.exists(path)) {
                return ToolExecutionResult.error("File not found: " + filePath);
            }

            if (!Files.isRegularFile(path)) {
                return ToolExecutionResult.error("Not a regular file: " + filePath);
            }

            List<String> lines = Files.readAllLines(path);

            Integer limit = input.get("limit") != null ? ((Number) input.get("limit")).intValue() : null;
            Integer offset = input.get("offset") != null ? ((Number) input.get("offset")).intValue() : 0;

            List<String> resultLines = offset >= 0 && offset < lines.size()
                ? (limit != null && limit > 0
                    ? lines.subList(offset, Math.min(offset + limit, lines.size()))
                    : lines.subList(offset, lines.size()))
                : List.of();

            StringBuilder result = new StringBuilder();
            for (int i = 0; i < resultLines.size(); i++) {
                result.append(offset + i + 1).append("\t").append(resultLines.get(i)).append("\n");
            }

            return ToolExecutionResult.success(result.toString());

        } catch (SecurityException e) {
            log.warn("Path traversal attempt: {}", filePath);
            return ToolExecutionResult.error("Security error: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to read file: {}", filePath, e);
            return ToolExecutionResult.error("Failed to read file: " + e.getMessage());
        }
    }
}
