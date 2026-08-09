package com.javacodeagent.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FilePathResolverTest {

    private FilePathResolver resolver;
    private Path workingDirectory;
    private Path outsideDirectory;

    @BeforeEach
    void setUp() throws IOException {
        resolver = new FilePathResolver();
        workingDirectory = Files.createTempDirectory("workdir");
        outsideDirectory = Files.createTempDirectory("outside");
        Files.writeString(outsideDirectory.resolve("secret.txt"), "top secret");
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteRecursively(workingDirectory);
        deleteRecursively(outsideDirectory);
    }

    @Test
    void plainRelativePath_resolvesInsideWorkingDirectory() {
        Path resolved = resolver.resolve("foo.txt", workingDirectory);
        assertEquals(workingDirectory.resolve("foo.txt"), resolved);
    }

    @Test
    void lexicalTraversal_isRejected() {
        assertThrows(SecurityException.class, () -> resolver.resolve("../secret.txt", workingDirectory));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void symlinkEscapingWorkingDirectory_isRejected() throws IOException {
        // workdir/link -> outsideDirectory ；词法上 normalize() 无法识别这是逃逸，
        // 必须解析真实路径（toRealPath）才能发现 link 实际指向 workingDirectory 之外
        Path link = workingDirectory.resolve("link");
        Files.createSymbolicLink(link, outsideDirectory);

        assertThrows(SecurityException.class,
            () -> resolver.resolve("link/secret.txt", workingDirectory));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void symlinkStayingInsideWorkingDirectory_isAllowed() throws IOException {
        Path realFile = workingDirectory.resolve("real.txt");
        Files.writeString(realFile, "hello");
        Path link = workingDirectory.resolve("link.txt");
        Files.createSymbolicLink(link, realFile);

        Path resolved = resolver.resolve("link.txt", workingDirectory);
        assertEquals(workingDirectory.resolve("link.txt"), resolved);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best-effort cleanup
                    }
                });
        }
    }
}
