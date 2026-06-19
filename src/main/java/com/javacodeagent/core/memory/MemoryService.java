package com.javacodeagent.core.memory;

import com.javacodeagent.config.MemoryConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 记忆服务 - 支持文件级持久化和 MEMORY.md 索引
 * <p>
 * 存储结构：
 * ${memory.location}/
 * ├── MEMORY.md                    # 索引文件（每行≤150字符）
 * ├── user_role.md                 # 用户记忆
 * ├── feedback_testing.md          # 反馈记忆
 * └── project_current.md           # 项目记忆
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final MemoryConfig memoryConfig;

    /**
     * 内存缓存：userId → (memoryId → MemoryEntry)
     */
    private final Map<String, Map<String, MemoryEntry>> userMemories = new ConcurrentHashMap<>();

    /**
     * 索引文件中 [[name]] 链接模式
     */
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[\\[([^\\]]+)\\]\\]");

    /**
     * 前导元数据分隔符
     */
    private static final String FRONTMATTER_DELIMITER = "---";

    @PostConstruct
    public void init() {
        if (memoryConfig.isEnabled()) {
            Path memoryDir = Paths.get(memoryConfig.getLocation());
            try {
                Files.createDirectories(memoryDir);
                log.info("Memory directory initialized: {}", memoryDir.toAbsolutePath());
                loadAllMemories();
            } catch (IOException e) {
                log.error("Failed to initialize memory directory", e);
            }
        }
    }

    /**
     * 保存记忆（同时持久化到文件）
     */
    public void saveMemory(MemoryEntry entry) {
        entry.setId(UUID.randomUUID().toString());
        entry.setCreatedAt(LocalDateTime.now());
        entry.setUpdatedAt(LocalDateTime.now());

        // 内存缓存
        userMemories
            .computeIfAbsent(entry.getUserId(), id -> new ConcurrentHashMap<>())
            .put(entry.getId(), entry);

        // 文件持久化
        if (memoryConfig.isEnabled()) {
            persistMemoryToFile(entry);
            updateMemoryIndex();
        }
    }

    /**
     * 获取用户的所有记忆
     */
    public List<MemoryEntry> getUserMemories(String userId) {
        Map<String, MemoryEntry> memories = userMemories.get(userId);
        return memories != null ? new ArrayList<>(memories.values()) : List.of();
    }

    /**
     * 按类型获取记忆
     */
    public List<MemoryEntry> getMemoriesByType(String userId, MemoryType type) {
        Map<String, MemoryEntry> memories = userMemories.get(userId);
        if (memories == null) {
            return List.of();
        }

        return memories.values().stream()
            .filter(m -> m.getType() == type)
            .toList();
    }

    /**
     * 获取单条记忆
     */
    public MemoryEntry getMemory(String userId, String memoryId) {
        Map<String, MemoryEntry> memories = userMemories.get(userId);
        return memories != null ? memories.get(memoryId) : null;
    }

    /**
     * 删除记忆
     */
    public void deleteMemory(String userId, String memoryId) {
        Map<String, MemoryEntry> memories = userMemories.get(userId);
        if (memories != null) {
            MemoryEntry removed = memories.remove(memoryId);
            if (removed != null && memoryConfig.isEnabled()) {
                deleteMemoryFile(removed);
                updateMemoryIndex();
            }
        }
    }

    /**
     * 更新记忆
     */
    public void updateMemory(String userId, MemoryEntry entry) {
        entry.setUpdatedAt(LocalDateTime.now());
        Map<String, MemoryEntry> memories = userMemories.get(userId);
        if (memories != null && memories.containsKey(entry.getId())) {
            memories.put(entry.getId(), entry);
            if (memoryConfig.isEnabled()) {
                persistMemoryToFile(entry);
                updateMemoryIndex();
            }
        }
    }

    /**
     * 搜索记忆（多字段匹配：content / name / description，大小写不敏感）
     *
     * 比原来的单字段 content.contains() 覆盖面更广：
     *   - name 字段：关键词命中记忆名称（slug）
     *   - description 字段：命中一行摘要
     *   - content 字段：命中正文
     */
    public List<MemoryEntry> searchMemories(String userId, String keyword) {
        Map<String, MemoryEntry> memories = userMemories.get(userId);
        if (memories == null) {
            return List.of();
        }

        String lower = keyword.toLowerCase();
        return memories.values().stream()
            .filter(m -> matchesKeyword(m, lower))
            .collect(Collectors.toList());
    }

    private boolean matchesKeyword(MemoryEntry m, String lower) {
        if (m.getContent() != null && m.getContent().toLowerCase().contains(lower)) return true;
        if (m.getName() != null && m.getName().toLowerCase().contains(lower)) return true;
        if (m.getDescription() != null && m.getDescription().toLowerCase().contains(lower)) return true;
        return false;
    }

    // ========== 文件持久化 ==========

    /**
     * 将记忆持久化到文件。
     *
     * 存储路径：{memory.location}/{userId}/{name}.md
     * 多用户各自使用独立子目录，互不干扰。
     */
    private void persistMemoryToFile(MemoryEntry entry) {
        try {
            // 按 userId 创建独立子目录
            String safeUserId = sanitizeDirName(
                entry.getUserId() != null ? entry.getUserId() : "default");
            Path userDir = Paths.get(memoryConfig.getLocation()).resolve(safeUserId);
            Files.createDirectories(userDir);

            String fileName = entry.getName() != null
                ? entry.getName() + ".md"
                : entry.getId() + ".md";
            Path filePath = userDir.resolve(fileName);

            StringBuilder content = new StringBuilder();
            // 前置元数据
            content.append(FRONTMATTER_DELIMITER).append("\n");
            content.append("name: ").append(entry.getName()).append("\n");
            content.append("description: ").append(entry.getDescription()).append("\n");
            content.append("userId: ").append(safeUserId).append("\n");
            content.append("metadata:\n");
            content.append("  type: ").append(entry.getType().name().toLowerCase()).append("\n");
            if (entry.getMetadata() != null) {
                for (Map.Entry<String, Object> meta : entry.getMetadata().entrySet()) {
                    content.append("  ").append(meta.getKey()).append(": ").append(meta.getValue()).append("\n");
                }
            }
            content.append(FRONTMATTER_DELIMITER).append("\n\n");
            content.append(entry.getContent()).append("\n");

            // 跨引用链接
            if (entry.getLinks() != null && !entry.getLinks().isEmpty()) {
                content.append("\n").append("关联记忆: ");
                content.append(entry.getLinks().stream()
                    .map(link -> "[[" + link + "]]")
                    .collect(Collectors.joining(", ")));
                content.append("\n");
            }

            Files.writeString(filePath, content.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            log.debug("Persisted memory to file: {}", filePath);

        } catch (IOException e) {
            log.error("Failed to persist memory to file", e);
        }
    }

    /**
     * 删除记忆文件（支持 userId 子目录和旧版平铺目录）
     */
    private void deleteMemoryFile(MemoryEntry entry) {
        try {
            String safeUserId = sanitizeDirName(
                entry.getUserId() != null ? entry.getUserId() : "default");
            String fileName = entry.getName() != null
                ? entry.getName() + ".md"
                : entry.getId() + ".md";

            // 优先删除子目录下的文件
            Path userDir = Paths.get(memoryConfig.getLocation()).resolve(safeUserId);
            Path filePath = userDir.resolve(fileName);
            if (!Files.deleteIfExists(filePath)) {
                // 兼容旧版平铺目录
                filePath = Paths.get(memoryConfig.getLocation()).resolve(fileName);
                Files.deleteIfExists(filePath);
            }
            log.debug("Deleted memory file: {}", filePath);

        } catch (IOException e) {
            log.error("Failed to delete memory file", e);
        }
    }

    /**
     * 更新 MEMORY.md 索引文件
     * 每行格式: - [Title](file.md) — one-line hook（≤150字符）
     */
    private void updateMemoryIndex() {
        try {
            Path memoryDir = Paths.get(memoryConfig.getLocation());
            Path indexPath = memoryDir.resolve(memoryConfig.getIndexFile());

            StringBuilder index = new StringBuilder();
            index.append("# Memory Index\n\n");

            List<MemoryEntry> allMemories = userMemories.values().stream()
                .flatMap(m -> m.values().stream())
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .collect(Collectors.toList());

            for (MemoryEntry entry : allMemories) {
                // 记忆文件存储在 {memory.location}/{userId}/{name}.md，索引链接须含 userId 前缀
                String userPrefix = (entry.getUserId() != null && !entry.getUserId().isBlank())
                    ? entry.getUserId() + "/"
                    : "";
                String fileName = userPrefix + (entry.getName() != null
                    ? entry.getName() + ".md"
                    : entry.getId() + ".md");

                String title = entry.getName() != null
                    ? entry.getName().replace("-", " ").replace("_", " ")
                    : entry.getId();

                String line = String.format("- [%s](%s) — %s",
                    capitalize(title), fileName, entry.getDescription());

                // 截断到150字符
                if (line.length() > 150) {
                    line = line.substring(0, 147) + "...";
                }

                index.append(line).append("\n");
            }

            Files.writeString(indexPath, index.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            log.debug("Updated memory index: {} entries", allMemories.size());

        } catch (IOException e) {
            log.error("Failed to update memory index", e);
        }
    }

    /**
     * 从文件加载所有记忆。
     *
     * 支持两种目录结构：
     *   新版：{memory.location}/{userId}/*.md  —— userId 从子目录名提取
     *   旧版：{memory.location}/*.md           —— userId 退化为 "default"
     */
    private void loadAllMemories() {
        Path memoryDir = Paths.get(memoryConfig.getLocation());
        if (!Files.exists(memoryDir)) {
            return;
        }

        try (Stream<Path> entries = Files.list(memoryDir)) {
            entries.forEach(entry -> {
                if (Files.isDirectory(entry)) {
                    // 新版：子目录名即 userId
                    String userId = entry.getFileName().toString();
                    try (Stream<Path> files = Files.list(entry)) {
                        files.filter(p -> p.toString().endsWith(".md")
                                && !p.getFileName().toString().equals(memoryConfig.getIndexFile()))
                            .forEach(f -> loadMemoryFromFile(f, userId));
                    } catch (IOException e) {
                        log.error("Failed to scan memory sub-dir: {}", entry, e);
                    }
                } else if (entry.toString().endsWith(".md")
                        && !entry.getFileName().toString().equals(memoryConfig.getIndexFile())) {
                    // 旧版平铺文件，userid = "default"
                    loadMemoryFromFile(entry, "default");
                }
            });
        } catch (IOException e) {
            log.error("Failed to load memories from disk", e);
        }
    }

    /**
     * 从文件加载单条记忆，userId 由调用方传入（从目录结构推断）。
     * frontmatter 中若存在 userId 字段则以它为准（优先级高于目录名）。
     */
    private void loadMemoryFromFile(Path filePath, String defaultUserId) {
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);

            // 解析前置元数据
            String name = filePath.getFileName().toString().replace(".md", "");
            String description = "";
            MemoryType type = MemoryType.USER;
            String userId = defaultUserId;
            List<String> links = new ArrayList<>();
            StringBuilder body = new StringBuilder();

            String[] parts = content.split(FRONTMATTER_DELIMITER, 3);
            if (parts.length >= 3) {
                // 解析 YAML 格式的前置元数据
                String yaml = parts[1];
                for (String line : yaml.split("\n")) {
                    line = line.trim();
                    if (line.startsWith("name:")) {
                        name = line.substring(5).trim();
                    } else if (line.startsWith("description:")) {
                        description = line.substring(12).trim();
                    } else if (line.startsWith("userId:")) {
                        // frontmatter 中明确记录的 userId 优先于目录名
                        String fmUserId = line.substring(7).trim();
                        if (!fmUserId.isEmpty()) userId = fmUserId;
                    } else if (line.startsWith("type:")) {
                        try {
                            type = MemoryType.valueOf(line.substring(5).trim().toUpperCase());
                        } catch (IllegalArgumentException e) {
                            type = MemoryType.USER;
                        }
                    }
                }

                // 解析正文
                body.append(parts[2].trim());

                // 提取 [[name]] 链接
                Matcher matcher = LINK_PATTERN.matcher(body);
                while (matcher.find()) {
                    links.add(matcher.group(1));
                }
            } else {
                body.append(content.trim());
            }

            MemoryEntry entry = MemoryEntry.builder()
                .id(UUID.nameUUIDFromBytes(filePath.toString().getBytes()).toString())
                .userId(userId)
                .name(name)
                .description(description)
                .type(type)
                .content(body.toString())
                .links(links)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

            userMemories
                .computeIfAbsent(userId, id -> new ConcurrentHashMap<>())
                .put(entry.getId(), entry);

            log.debug("Loaded memory from file: {} (userId={})", filePath.getFileName(), userId);

        } catch (IOException e) {
            log.error("Failed to load memory from file: {}", filePath, e);
        }
    }

    /**
     * 将 userId 转义为安全的目录名（去掉路径分隔符等特殊字符）
     */
    private String sanitizeDirName(String userId) {
        if (userId == null || userId.isBlank()) return "default";
        // 保留字母、数字、连字符、下划线、点；其余替换为 _
        return userId.replaceAll("[^a-zA-Z0-9\\-_.]", "_").toLowerCase();
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        String[] words = str.split(" ");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            result.append(Character.toUpperCase(word.charAt(0)))
                .append(word.substring(1))
                .append(" ");
        }
        return result.toString().trim();
    }
}
