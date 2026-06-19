package com.javacodeagent.integration;

import com.javacodeagent.core.enums.PermissionType;
import com.javacodeagent.core.model.ExecutionContext;
import com.javacodeagent.core.model.ToolCall;
import com.javacodeagent.core.model.ToolExecutionResult;
import com.javacodeagent.core.permission.PermissionService;
import com.javacodeagent.core.tool.ToolManager;
import com.javacodeagent.tools.GlobTool;
import com.javacodeagent.tools.GrepTool;
import com.javacodeagent.tools.ReadTool;
import com.javacodeagent.tools.WriteTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具执行集成测试。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>Read / Write / Glob / Grep 工具的完整执行路径</li>
 *   <li>ToolManager 权限检查</li>
 *   <li>Hook 前/后拦截链不阻断正常调用</li>
 *   <li>未知工具名称的错误路径</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
    "security.api-key=",          // 关闭 API 认证，避免测试需要带 key
    "llm.api-key=test-key",
    "llm.provider=anthropic"
})
class ToolManagerIntegrationTest {

    @Autowired
    private ToolManager toolManager;

    @Autowired
    private PermissionService permissionService;

    @TempDir
    Path tempDir;

    private ExecutionContext ctx;

    @BeforeEach
    void setUp() throws IOException {
        // 全部 FILE_WRITE 权限开放（测试用）
        permissionService.setAutoApproved("test-user", PermissionType.FILE_WRITE, true);

        ctx = ExecutionContext.builder()
            .userId("test-user")
            .conversationId("conv-it-001")
            .workingDirectory(tempDir)
            .build();

        // 准备基础文件
        Files.writeString(tempDir.resolve("hello.txt"), "Hello World\nLine Two\n");
        Files.writeString(tempDir.resolve("foo.java"), "public class Foo { void bar() {} }");
    }

    // ------------------------------------------------------------------
    // Read 工具
    // ------------------------------------------------------------------

    @Test
    void readTool_existingFile_returnsContent() {
        ToolCall tc = ToolCall.builder()
            .id("tc-read-1")
            .name("Read")
            .input(Map.of("file_path", tempDir.resolve("hello.txt").toString()))
            .build();

        ToolExecutionResult result = toolManager.executeToolCall(tc, ctx);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("Hello World");
    }

    @Test
    void readTool_missingFile_returnsError() {
        ToolCall tc = ToolCall.builder()
            .id("tc-read-2")
            .name("Read")
            .input(Map.of("file_path", "/non/existent/file.txt"))
            .build();

        ToolExecutionResult result = toolManager.executeToolCall(tc, ctx);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).isNotBlank();
    }

    // ------------------------------------------------------------------
    // Write 工具
    // ------------------------------------------------------------------

    @Test
    void writeTool_createsNewFile() {
        Path target = tempDir.resolve("new_file.txt");
        ToolCall tc = ToolCall.builder()
            .id("tc-write-1")
            .name("Write")
            .input(Map.of(
                "file_path", target.toString(),
                "content", "integration test content"))
            .build();

        ToolExecutionResult result = toolManager.executeToolCall(tc, ctx);

        assertThat(result.isSuccess()).isTrue();
        assertThat(target).exists();
        assertThat(target).hasContent("integration test content");
    }

    @Test
    void writeTool_overwritesExistingFile() throws IOException {
        Path target = tempDir.resolve("hello.txt");
        ToolCall tc = ToolCall.builder()
            .id("tc-write-2")
            .name("Write")
            .input(Map.of(
                "file_path", target.toString(),
                "content", "overwritten"))
            .build();

        toolManager.executeToolCall(tc, ctx);

        assertThat(Files.readString(target)).isEqualTo("overwritten");
    }

    // ------------------------------------------------------------------
    // Glob 工具
    // ------------------------------------------------------------------

    @Test
    void globTool_matchesPattern() {
        ToolCall tc = ToolCall.builder()
            .id("tc-glob-1")
            .name("Glob")
            .input(Map.of("pattern", "**/*.java"))
            .build();

        ToolExecutionResult result = toolManager.executeToolCall(tc, ctx);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("foo.java");
    }

    @Test
    void globTool_noMatch_returnsEmpty() {
        ToolCall tc = ToolCall.builder()
            .id("tc-glob-2")
            .name("Glob")
            .input(Map.of("pattern", "**/*.nonexistent"))
            .build();

        ToolExecutionResult result = toolManager.executeToolCall(tc, ctx);

        // 未匹配不应报错，而是返回空结果
        assertThat(result.isSuccess()).isTrue();
    }

    // ------------------------------------------------------------------
    // Grep 工具
    // ------------------------------------------------------------------

    @Test
    void grepTool_findsPattern() {
        ToolCall tc = ToolCall.builder()
            .id("tc-grep-1")
            .name("Grep")
            .input(Map.of("pattern", "Hello"))
            .build();

        ToolExecutionResult result = toolManager.executeToolCall(tc, ctx);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("hello.txt");
    }

    // ------------------------------------------------------------------
    // 未知工具
    // ------------------------------------------------------------------

    @Test
    void unknownTool_returnsError() {
        ToolCall tc = ToolCall.builder()
            .id("tc-unknown-1")
            .name("NonExistentTool")
            .input(Map.of())
            .build();

        ToolExecutionResult result = toolManager.executeToolCall(tc, ctx);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("not found");
    }

    // ------------------------------------------------------------------
    // 工具注册验证
    // ------------------------------------------------------------------

    @Test
    void toolManager_registersExpectedTools() {
        assertThat(toolManager.hasTool("Read")).isTrue();
        assertThat(toolManager.hasTool("Write")).isTrue();
        assertThat(toolManager.hasTool("Edit")).isTrue();
        assertThat(toolManager.hasTool("Glob")).isTrue();
        assertThat(toolManager.hasTool("Grep")).isTrue();
        assertThat(toolManager.hasTool("Bash")).isTrue();
    }

    // ------------------------------------------------------------------
    // Read 分页参数
    // ------------------------------------------------------------------

    @Test
    void readTool_withLimitAndOffset_returnsSubset() throws IOException {
        // 写入 5 行
        Files.writeString(tempDir.resolve("lines.txt"),
            "line1\nline2\nline3\nline4\nline5\n");

        ToolCall tc = ToolCall.builder()
            .id("tc-read-limit")
            .name("Read")
            .input(Map.of(
                "file_path", tempDir.resolve("lines.txt").toString(),
                "limit", 2,
                "offset", 1))
            .build();

        ToolExecutionResult result = toolManager.executeToolCall(tc, ctx);

        assertThat(result.isSuccess()).isTrue();
        // offset=1 跳过 line1，limit=2 取 line2、line3
        assertThat(result.getContent()).contains("line2");
        assertThat(result.getContent()).doesNotContain("line1");
    }
}
