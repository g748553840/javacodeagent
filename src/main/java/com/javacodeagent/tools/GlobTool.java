package com.javacodeagent.tools;

import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.core.model.ToolExecutionResult;
import com.javacodeagent.core.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class GlobTool implements Tool {

    private static final int MAX_WALK_DEPTH = 12;

    private final FilePathResolver pathResolver;

    @Override
    public String getName() {
        return "Glob";
    }

    @Override
    public String getDescription() {
        return "Fast file pattern matching tool. Use **/*.java for recursive pattern.";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> patternProp = new HashMap<>();
        patternProp.put("type", "string");
        patternProp.put("description", "The glob pattern to match files (e.g., **/*.java)");
        properties.put("pattern", patternProp);

        Map<String, Object> pathProp = new HashMap<>();
        pathProp.put("type", "string");
        pathProp.put("description", "The base directory to search in (default: working directory)");
        properties.put("path", pathProp);

        schema.put("properties", properties);

        List<String> required = List.of("pattern");
        schema.put("required", required);

        return schema;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> input, ExecutionContext context) {
        String pattern = (String) input.get("pattern");

        try {
            Path baseDir;
            if (input.get("path") != null) {
                baseDir = pathResolver.resolve((String) input.get("path"), context.getWorkingDirectory());
            } else if (context.getWorkingDirectory() != null) {
                baseDir = context.getWorkingDirectory();
            } else {
                baseDir = pathResolver.resolve(".", null);
            }

            if (!Files.exists(baseDir) || !Files.isDirectory(baseDir)) {
                return ToolExecutionResult.error("Directory not found: " + baseDir);
            }

            List<String> matchedFiles = new ArrayList<>();

            try (Stream<Path> paths = Files.walk(baseDir, MAX_WALK_DEPTH)) {
                // PathMatcher 对绝对路径匹配行为不一致：
                //   glob:*.java   → 只匹配不含 '/' 的单段路径，绝对路径永远不匹配
                //   glob:**/*.java → 需要完整路径字符串与模式对齐
                // 解决：统一转换为相对于 baseDir 的相对路径再做匹配，两种写法均正确工作
                final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

                matchedFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        Path relative = baseDir.relativize(p);
                        return matcher.matches(relative);
                    })
                    .map(p -> p.toAbsolutePath().toString())
                    .sorted()
                    .collect(Collectors.toList());
            }

            StringBuilder result = new StringBuilder();
            for (String file : matchedFiles) {
                result.append(file).append("\n");
            }

            return ToolExecutionResult.success(result.toString());

        } catch (SecurityException e) {
            log.warn("Path traversal attempt in glob");
            return ToolExecutionResult.error("Security error: " + e.getMessage());
        } catch (Exception e) {
            log.error("Glob failed for pattern: {}", pattern, e);
            return ToolExecutionResult.error("Glob failed: " + e.getMessage());
        }
    }
}
