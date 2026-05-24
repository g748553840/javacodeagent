package com.javacodeagent.tools;

import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.core.model.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReadToolTest {

    private ReadTool readTool;
    private Path testFile;

    @BeforeEach
    void setUp() throws Exception {
        readTool = new ReadTool(new FilePathResolver());
        testFile = Files.createTempFile("test", ".txt");
        Files.writeString(testFile, "Line 1\nLine 2\nLine 3\nLine 4\nLine 5");
    }

    @Test
    void testReadFileSuccess() {
        Map<String, Object> input = new HashMap<>();
        input.put("file_path", testFile.toString());

        ExecutionContext context = ExecutionContext.builder()
            .userId("test-user")
            .build();

        ToolExecutionResult result = readTool.execute(input, context);

        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("Line 1"));
    }

    @Test
    void testReadFileNotFound() {
        Map<String, Object> input = new HashMap<>();
        input.put("file_path", "/non/existent/file.txt");

        ExecutionContext context = ExecutionContext.builder()
            .userId("test-user")
            .build();

        ToolExecutionResult result = readTool.execute(input, context);

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("not found"));
    }

    @Test
    void testReadFileWithLimit() {
        Map<String, Object> input = new HashMap<>();
        input.put("file_path", testFile.toString());
        input.put("limit", 2);

        ExecutionContext context = ExecutionContext.builder()
            .userId("test-user")
            .build();

        ToolExecutionResult result = readTool.execute(input, context);

        assertTrue(result.isSuccess());
        String[] lines = result.getContent().split("\n");
        assertEquals(2, lines.length);
    }
}
