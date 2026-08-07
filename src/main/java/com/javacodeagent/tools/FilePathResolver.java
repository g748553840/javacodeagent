package com.javacodeagent.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
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
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("file_path must not be blank");
        }

        Path path = Paths.get(filePath).normalize();

        if (!path.isAbsolute()) {
            if (workingDirectory != null) {
                path = workingDirectory.resolve(path).normalize();
            } else {
                path = Paths.get(".").toAbsolutePath().resolve(path).normalize();
            }
        } else if (workingDirectory == null) {
            // 未配置工作目录时拒绝绝对路径，防止越权访问任意系统文件
            throw new SecurityException(
                "Absolute paths are not permitted when no working directory is configured: " + filePath);
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

            // normalize() 只做词法层面的 ".."/"." 折叠，不会解析符号链接：
            // 若 workdir 内存在指向 workdir 外部的符号链接（如 workdir/link -> /etc/passwd），
            // normalizedPath 仍然以 normalizedWd 开头，上面的 startsWith 检查会被绕过。
            // 因此需要额外解析真实路径（toRealPath）再做一次边界校验；
            // 若文件尚不存在（如 Write 创建新文件），改为校验其最近一层已存在的祖先目录的真实路径。
            Path realWd = resolveRealPath(normalizedWd);
            Path realTarget = resolveNearestExistingRealPath(normalizedPath);

            if (!realTarget.startsWith(realWd)) {
                throw new SecurityException(
                    "Path traversal detected via symlink: " + filePath + " resolves outside working directory " + normalizedWd
                );
            }
        }

        return path;
    }

    /** 解析路径的真实路径（跟随符号链接）；若解析失败则退化为原始规范化路径。 */
    private Path resolveRealPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path;
        }
    }

    /**
     * 解析目标路径本身（若存在）或其最近一层已存在祖先目录的真实路径。
     * 用于在目标文件尚未创建时（如 Write 新文件）依然能校验符号链接逃逸。
     */
    private Path resolveNearestExistingRealPath(Path path) {
        Path candidate = path;
        while (candidate != null) {
            if (Files.exists(candidate)) {
                return resolveRealPath(candidate);
            }
            candidate = candidate.getParent();
        }
        return resolveRealPath(path);
    }
}
