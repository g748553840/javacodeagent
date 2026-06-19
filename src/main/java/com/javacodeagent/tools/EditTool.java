package com.javacodeagent.tools;

import com.javacodeagent.core.enums.PermissionType;
import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.core.model.ToolExecutionResult;
import com.javacodeagent.core.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class EditTool implements Tool {

    private final FilePathResolver pathResolver;

    @Override
    public String getName() {
        return "Edit";
    }

    @Override
    public String getDescription() {
        return "Performs exact string replacements in files";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> filePathProp = new HashMap<>();
        filePathProp.put("type", "string");
        filePathProp.put("description", "The absolute path to the file to modify");
        properties.put("file_path", filePathProp);

        Map<String, Object> oldStrProp = new HashMap<>();
        oldStrProp.put("type", "string");
        oldStrProp.put("description", "The text to replace");
        properties.put("old_string", oldStrProp);

        Map<String, Object> newStrProp = new HashMap<>();
        newStrProp.put("type", "string");
        newStrProp.put("description", "The text to replace it with");
        properties.put("new_string", newStrProp);

        Map<String, Object> replaceAllProp = new HashMap<>();
        replaceAllProp.put("type", "boolean");
        replaceAllProp.put("description", "Replace all occurrences (default: false)");
        properties.put("replace_all", replaceAllProp);

        schema.put("properties", properties);

        List<String> required = List.of("file_path", "old_string", "new_string");
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

    @Override
    public ToolExecutionResult execute(Map<String, Object> input, ExecutionContext context) {
        String filePath = (String) input.get("file_path");
        String oldString = (String) input.get("old_string");
        String newString = (String) input.get("new_string");
        boolean replaceAll = input.get("replace_all") != null && (Boolean) input.get("replace_all");

        try {
            Path path = pathResolver.resolve(filePath, context.getWorkingDirectory());

            if (!Files.exists(path)) {
                return ToolExecutionResult.error("File not found: " + filePath);
            }

            String content = Files.readString(path, StandardCharsets.UTF_8);

            if (!content.contains(oldString)) {
                return ToolExecutionResult.error("old_string not found in file: " + filePath);
            }

            // replaceFirst 的第一个参数是正则，必须对 oldString 做字面量转义
            String newContent = replaceAll
                ? content.replace(oldString, newString)
                : content.replaceFirst(Pattern.quote(oldString), Matcher.quoteReplacement(newString));

            Files.writeString(path, newContent, StandardCharsets.UTF_8);

            return ToolExecutionResult.success("File edited successfully: " + filePath);

        } catch (SecurityException e) {
            log.warn("Path traversal attempt: {}", filePath);
            return ToolExecutionResult.error("Security error: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to edit file: {}", filePath, e);
            return ToolExecutionResult.error("Failed to edit file: " + e.getMessage());
        }
    }
}
