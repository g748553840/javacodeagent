package com.javacodeagent.tools;

import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.core.model.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BashToolTest {

    private BashTool bashTool;

    @BeforeEach
    void setUp() {
        bashTool = new BashTool(new BackgroundTaskExecutor());
    }

    @Test
    void testEchoCommand() {
        Map<String, Object> input = new HashMap<>();
        String os = System.getProperty("os.name").toLowerCase();
        String command = os.contains("win") ? "echo hello" : "echo 'hello'";
        input.put("command", command);

        ExecutionContext context = ExecutionContext.builder()
            .userId("test-user")
            .build();

        ToolExecutionResult result = bashTool.execute(input, context);

        assertTrue(result.isSuccess());
        assertTrue(result.getContent().toLowerCase().contains("hello"));
    }

    @Test
    void testListDirectory() {
        Map<String, Object> input = new HashMap<>();
        String os = System.getProperty("os.name").toLowerCase();
        String command = os.contains("win") ? "dir" : "ls";
        input.put("command", command);

        ExecutionContext context = ExecutionContext.builder()
            .userId("test-user")
            .build();

        ToolExecutionResult result = bashTool.execute(input, context);

        assertTrue(result.isSuccess());
        assertNotNull(result.getContent());
    }
}
