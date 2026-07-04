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

            Integer limit = input.get("limit") != null ? ((Number) input.get("limit")).intValue() : null;
            Integer offset = input.get("offset") != null ? ((Number) input.get("offset")).intValue() : 0;
            final int safeOffset = (offset != null && offset >= 0) ? offset : 0;

            // 使用 Files.lines() 懒加载，只读取需要的行，避免大文件 OOM
            StringBuilder result = new StringBuilder();
            try (java.util.stream.Stream<String> stream = Files.lines(path, java.nio.charset.StandardCharsets.UTF_8)) {
                java.util.stream.Stream<String> s = stream.skip(safeOffset);
                if (limit != null && limit > 0) {
                    s = s.limit(limit);
                }
                final int[] lineNum = {safeOffset + 1};
                s.forEach(line -> result.append(lineNum[0]++).append("\t").append(line).append("\n"));
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
