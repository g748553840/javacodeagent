package com.javacodeagent.tools;

import com.javacodeagent.core.enums.PermissionType;
import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.core.model.ToolExecutionResult;
import com.javacodeagent.core.tool.Tool;
import com.javacodeagent.piagent.tool.ToolExecutionMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class WriteTool implements Tool {

    private final FilePathResolver pathResolver;

    @Override
    public String getName() {
        return "Write";
    }

    @Override
    public String getDescription() {
        return "Write content to a file, overwriting any existing content";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> filePathProp = new HashMap<>();
        filePathProp.put("type", "string");
        filePathProp.put("description", "The absolute path to the file to write");
        properties.put("file_path", filePathProp);

        Map<String, Object> contentProp = new HashMap<>();
        contentProp.put("type", "string");
        contentProp.put("description", "The content to write to the file");
        properties.put("content", contentProp);

        schema.put("properties", properties);

        List<String> required = List.of("file_path", "content");
        schema.put("required", required);

        return schema;
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public PermissionType getRequiredPermission() {
        return PermissionType.FILE_WRITE;
    }

    /**
     * 写文件串行执行。
     *
     * <p>与 {@link EditTool#getExecutionMode()} 同理，且这里还多一层跨工具的风险：
     * 同一批里 Write 一个文件、再 Edit 同一个文件时，Edit 可能读到写入前的内容。
     * 由于「一批中有任一工具声明 SEQUENTIAL 则整批串行」，两者都声明串行，
     * 才能保证这种跨工具的先后依赖成立。
     */
    @Override
    public ToolExecutionMode getExecutionMode() {
        return ToolExecutionMode.SEQUENTIAL;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> input, ExecutionContext context) {
        String filePath = (String) input.get("file_path");
        String content = (String) input.get("content");

        if (content == null) {
            return ToolExecutionResult.error("content is required");
        }

        try {
            Path path = pathResolver.resolve(filePath, context.getWorkingDirectory());

            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            return ToolExecutionResult.success("File written successfully: " + filePath);

        } catch (SecurityException e) {
            log.warn("Path traversal attempt: {}", filePath);
            return ToolExecutionResult.error("Security error: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to write file: {}", filePath, e);
            return ToolExecutionResult.error("Failed to write file: " + e.getMessage());
        }
    }
}
