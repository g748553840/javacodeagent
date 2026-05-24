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
     * 搜索记忆（基于关键词匹配）
     */
    public List<MemoryEntry> searchMemories(String userId, String keyword) {
        Map<String, MemoryEntry> memories = userMemories.get(userId);
        if (memories == null) {
            return List.of();
        }

        String lowerKeyword = keyword.toLowerCase();
        return memories.values().stream()
            .filter(m -> m.getContent() != null
                && m.getContent().toLowerCase().contains(lowerKeyword))
            .collect(Collectors.toList());
    }

    // ========== 文件持久化 ==========

    /**
     * 将记忆持久化到文件
     */
    private void persistMemoryToFile(MemoryEntry entry) {
        try {
            Path memoryDir = Paths.get(memoryConfig.getLocation());
            Files.createDirectories(memoryDir);

            String fileName = entry.getName() != null
                ? entry.getName() + ".md"
                : entry.getId() + ".md";
            Path filePath = memoryDir.resolve(fileName);

            StringBuilder content = new StringBuilder();
            // 前置元数据
            content.append(FRONTMATTER_DELIMITER).append("\n");
            content.append("name: ").append(entry.getName()).append("\n");
            content.append("description: ").append(entry.getDescription()).append("\n");
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
     * 删除记忆文件
     */
    private void deleteMemoryFile(MemoryEntry entry) {
        try {
            Path memoryDir = Paths.get(memoryConfig.getLocation());
            String fileName = entry.getName() != null
                ? entry.getName() + ".md"
                : entry.getId() + ".md";
            Path filePath = memoryDir.resolve(fileName);

            Files.deleteIfExists(filePath);
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
                String fileName = entry.getName() != null
                    ? entry.getName() + ".md"
                    : entry.getId() + ".md";

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
     * 从文件加载所有记忆
     */
    private void loadAllMemories() {
        Path memoryDir = Paths.get(memoryConfig.getLocation());
        if (!Files.exists(memoryDir)) {
            return;
        }

        try (Stream<Path> files = Files.list(memoryDir)) {
            files.filter(p -> p.toString().endsWith(".md")
                    && !p.getFileName().toString().equals(memoryConfig.getIndexFile()))
                .forEach(this::loadMemoryFromFile);
        } catch (IOException e) {
            log.error("Failed to load memories from disk", e);
        }
    }

    /**
     * 从文件加载单条记忆
     */
    private void loadMemoryFromFile(Path filePath) {
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);

            // 解析前置元数据
            String name = filePath.getFileName().toString().replace(".md", "");
            String description = "";
            MemoryType type = MemoryType.USER;
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
                .name(name)
                .description(description)
                .type(type)
                .content(body.toString())
                .links(links)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

            userMemories
                .computeIfAbsent("default", id -> new ConcurrentHashMap<>())
                .put(entry.getId(), entry);

            log.debug("Loaded memory from file: {}", filePath.getFileName());

        } catch (IOException e) {
            log.error("Failed to load memory from file: {}", filePath, e);
        }
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
