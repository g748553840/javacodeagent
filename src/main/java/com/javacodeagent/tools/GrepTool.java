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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class GrepTool implements Tool {

    private final FilePathResolver pathResolver;

    @Override
    public String getName() {
        return "Grep";
    }

    @Override
    public String getDescription() {
        return "Search for a pattern in files. Supports regex and case-insensitive search.";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> patternProp = new HashMap<>();
        patternProp.put("type", "string");
        patternProp.put("description", "The regular expression pattern to search for");
        properties.put("pattern", patternProp);

        Map<String, Object> pathProp = new HashMap<>();
        pathProp.put("type", "string");
        pathProp.put("description", "The directory or file to search in (default: working directory)");
        properties.put("path", pathProp);

        Map<String, Object> globProp = new HashMap<>();
        globProp.put("type", "string");
        globProp.put("description", "Optional glob pattern to filter files (e.g., *.java)");
        properties.put("glob", globProp);

        Map<String, Object> caseInsensitiveProp = new HashMap<>();
        caseInsensitiveProp.put("type", "boolean");
        caseInsensitiveProp.put("description", "Case insensitive search (default: false)");
        properties.put("case_insensitive", caseInsensitiveProp);

        schema.put("properties", properties);

        List<String> required = List.of("pattern");
        schema.put("required", required);

        return schema;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> input, ExecutionContext context) {
        String pattern = (String) input.get("pattern");
        boolean caseInsensitive = input.get("case_insensitive") != null && (Boolean) input.get("case_insensitive");

        try {
            Path searchPath;
            if (input.get("path") != null) {
                searchPath = pathResolver.resolve((String) input.get("path"), context.getWorkingDirectory());
            } else if (context.getWorkingDirectory() != null) {
                searchPath = context.getWorkingDirectory();
            } else {
                searchPath = pathResolver.resolve(".", null);
            }

            if (!Files.exists(searchPath)) {
                return ToolExecutionResult.error("Path not found: " + searchPath);
            }

            int regexFlags = caseInsensitive ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
            Pattern compiledPattern = Pattern.compile(pattern, regexFlags);

            StringBuilder result = new StringBuilder();

            if (Files.isDirectory(searchPath)) {
                try (Stream<Path> fileStream = Files.walk(searchPath)) {
                    Stream<Path> filtered = fileStream.filter(Files::isRegularFile);

                    String glob = (String) input.get("glob");
                    if (glob != null && !glob.isEmpty()) {
                        String finalGlob = "glob:" + glob;
                        filtered = filtered.filter(p ->
                            FileSystems.getDefault().getPathMatcher(finalGlob).matches(p));
                    }

                    filtered.forEach(file -> searchInFile(file, compiledPattern, result));
                }
            } else {
                searchInFile(searchPath, compiledPattern, result);
            }

            if (result.isEmpty()) {
                return ToolExecutionResult.success("No matches found for pattern: " + pattern);
            }

            return ToolExecutionResult.success(result.toString());

        } catch (SecurityException e) {
            log.warn("Path traversal attempt in grep");
            return ToolExecutionResult.error("Security error: " + e.getMessage());
        } catch (Exception e) {
            log.error("Grep failed for pattern: {}", pattern, e);
            return ToolExecutionResult.error("Grep failed: " + e.getMessage());
        }
    }

    private void searchInFile(Path file, Pattern pattern, StringBuilder result) {
        try (Stream<String> lines = Files.lines(file)) {
            List<String> matchedLines = lines
                .filter(line -> pattern.matcher(line).find())
                .toList();

            if (!matchedLines.isEmpty()) {
                result.append(file.toString()).append(":\n");
                for (int i = 0; i < matchedLines.size(); i++) {
                    result.append("  ").append(i + 1).append(": ")
                        .append(matchedLines.get(i)).append("\n");
                }
                result.append("\n");
            }
        } catch (Exception e) {
            log.error("Error reading file: {}", file, e);
        }
    }
}
