package com.javacodeagent.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Component
public class FilePathResolver {

    /**
     * 解析并验证文件路径
     * - 相对路径基于工作目录解析
     * - 路径遍历防护：禁止目录逃逸
     * - 路径标准化
     *
     * @param filePath         用户提供的路径
     * @param workingDirectory 当前工作目录（可为null）
     * @return 解析后的标准化路径
     * @throws SecurityException 如果检测到路径遍历攻击
     */
    public Path resolve(String filePath, Path workingDirectory) {
        Path path = Paths.get(filePath).normalize();

        if (!path.isAbsolute()) {
            if (workingDirectory != null) {
                path = workingDirectory.resolve(path).normalize();
            } else {
                path = Paths.get(".").toAbsolutePath().resolve(path).normalize();
            }
        }

        // 路径遍历防护
        if (workingDirectory != null) {
            Path normalizedWd = workingDirectory.normalize().toAbsolutePath();
            Path normalizedPath = path.toAbsolutePath().normalize();

            if (!normalizedPath.startsWith(normalizedWd)) {
                throw new SecurityException(
                    "Path traversal detected: " + filePath + " escapes working directory " + normalizedWd
                );
            }
        }

        return path;
    }
}
