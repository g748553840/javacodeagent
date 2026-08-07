package com.javacodeagent.core.memory;

import com.javacodeagent.config.EmbeddingConfig;
import com.javacodeagent.config.MemoryConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * ├── {userId}/
 * │   ├── {name}.md               # 新版（userId 子目录）
 * │   └── ...
 * └── legacy_file.md               # 兼容旧版平铺目录（userId="default"）
 *
 * <p>
 * 搜索模式：
 * <ul>
 *   <li>关键词搜索（{@link #searchMemories}）：大小写不敏感，匹配 name/description/content 三个字段</li>
 *   <li>语义向量检索（{@link #semanticSearch}）：调用 Embedding API 生成向量，
 *       对用户记忆做余弦相似度排序，仅在 {@code memory.embedding.enabled=true} 时启用</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private static final int MEMORY_INDEX_MAX_LINE_LENGTH = 150;
    private static final int MEMORY_INDEX_TRUNCATE_LENGTH = 147; // MAX_LINE_LENGTH - 3 for "..."
    private static final int FRONTMATTER_PARTS = 3;
    // frontmatter field prefix lengths
    private static final int PREFIX_NAME = 5;        // "name:".length()
    private static final int PREFIX_DESCRIPTION = 12; // "description:".length()
    private static final int PREFIX_USER_ID = 7;      // "userId:".length()
    private static final int PREFIX_TYPE = 5;         // "type:".length()

    /** 单条记忆的最大 embedding 文本长度（防止超出模型 token 上限）。 */
    private static final int MAX_EMBED_TEXT_LENGTH = 8000;

    private final MemoryConfig memoryConfig;

    // ---- 向量检索（可选依赖，disabled 时为 null） ----

    /** Embedding 客户端，仅在 memory.embedding.enabled=true 时注入。 */
    @Autowired(required = false)
    private EmbeddingClient embeddingClient;

    /** 内存向量存储，仅在 memory.embedding.enabled=true 时注入。 */
    @Autowired(required = false)
    private MemoryEmbeddingStore embeddingStore;

    /** Embedding 配置，可选注入（disabled 时为 null）。 */
    @Autowired(required = false)
    private EmbeddingConfig embeddingConfig;

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
                // 启动时在后台批量计算缺失 embedding
                buildEmbeddingsInBackground();
            } catch (IOException e) {
                log.error("Failed to initialize memory directory", e);
            }
        }
    }

    // ========== 公共 API ==========

    /**
     * 保存记忆（同时持久化到文件）。
     * 若 Embedding 服务已启用，异步（fire-and-forget）为该条记忆生成向量。
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

        // 异步计算 embedding（不阻塞当前请求线程）
        computeAndStoreEmbeddingAsync(entry);
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
     * 删除记忆（同步更新内存 + 文件 + 向量存储）
     */
    public void deleteMemory(String userId, String memoryId) {
        Map<String, MemoryEntry> memories = userMemories.get(userId);
        if (memories != null) {
            MemoryEntry removed = memories.remove(memoryId);
            if (removed != null && memoryConfig.isEnabled()) {
                deleteMemoryFile(removed);
                updateMemoryIndex();
            }
            // 同步从向量存储中删除
            if (embeddingStore != null) {
                embeddingStore.remove(userId, memoryId);
            }
        }
    }

    /**
     * 更新记忆（同步更新内存 + 文件，重新计算 embedding）
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
            // 重新计算 embedding（旧向量已失效）
            if (embeddingStore != null) {
                embeddingStore.remove(userId, entry.getId());
            }
            computeAndStoreEmbeddingAsync(entry);
        }
    }

    /**
     * 关键词搜索（多字段匹配：content / name / description，大小写不敏感）。
     * <p>
     * 当 Embedding 服务已启用时，建议改用 {@link #semanticSearch}。
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

    /**
     * 语义向量检索（余弦相似度排序）。
     * <p>
     * 步骤：
     * <ol>
     *   <li>调用 {@link EmbeddingClient#embed} 生成查询向量</li>
     *   <li>在 {@link MemoryEmbeddingStore} 中对该用户全量向量做线性扫描</li>
     *   <li>过滤低于相似度阈值（{@code memory.embedding.similarity-threshold}）的结果</li>
     *   <li>返回按相似度倒序排列的 top-K 条记忆</li>
     * </ol>
     * <p>
     * 若 Embedding 服务未启用，或查询向量生成失败，自动降级为关键词搜索。
     *
     * @param userId 用户 ID
     * @param query  自然语言查询
     * @param topK   最多返回条数（≤ 0 时使用配置默认值）
     * @return 按相似度倒序排列的记忆列表
     */
    public Mono<List<MemoryEntry>> semanticSearch(String userId, String query, int topK) {
        // 未启用 embedding → 降级为关键词搜索
        if (embeddingClient == null || embeddingStore == null || embeddingConfig == null) {
            log.debug("Embedding not enabled, falling back to keyword search for: {}", query);
            return Mono.just(searchMemories(userId, query));
        }

        int effectiveTopK = topK > 0 ? topK : embeddingConfig.getTopK();
        double threshold   = embeddingConfig.getSimilarityThreshold();

        return embeddingClient.embed(query)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(queryVec -> {
                    if (queryVec.length == 0) {
                        log.warn("Query embedding returned empty vector, falling back to keyword search");
                        return Mono.just(searchMemories(userId, query));
                    }

                    Map<String, MemoryEntry> memories = userMemories.get(userId);
                    if (memories == null) return Mono.just(List.<MemoryEntry>of());

                    List<MemoryEmbeddingStore.ScoredMemoryId> scored =
                            embeddingStore.findTopK(userId, queryVec, effectiveTopK, threshold);

                    List<MemoryEntry> result = scored.stream()
                            .map(s -> memories.get(s.memoryId()))
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());

                    log.debug("Semantic search for '{}': {} results (topK={}, threshold={})",
                            query, result.size(), effectiveTopK, threshold);
                    return Mono.just(result);
                })
                .onErrorResume(e -> {
                    log.warn("Semantic search failed, falling back to keyword: {}", e.getMessage());
                    return Mono.just(searchMemories(userId, query));
                });
    }

    /**
     * 返回当前 Embedding 服务是否已启用并可用。
     */
    public boolean isEmbeddingEnabled() {
        return embeddingClient != null && embeddingStore != null;
    }

    // ========== 内部工具 ==========

    private boolean matchesKeyword(MemoryEntry m, String lower) {
        if (m.getContent() != null && m.getContent().toLowerCase().contains(lower)) return true;
        if (m.getName() != null && m.getName().toLowerCase().contains(lower)) return true;
        if (m.getDescription() != null && m.getDescription().toLowerCase().contains(lower)) return true;
        return false;
    }

    /**
     * 异步为单条记忆计算并存储 embedding（fire-and-forget，不阻塞调用线程）。
     */
    private void computeAndStoreEmbeddingAsync(MemoryEntry entry) {
        if (embeddingClient == null || embeddingStore == null) return;
        String text = buildEmbeddingText(entry);
        embeddingClient.embed(text)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        vec -> embeddingStore.put(entry.getUserId(), entry.getId(), vec),
                        err -> log.warn("Failed to compute embedding for memory '{}': {}",
                                entry.getName(), err.getMessage())
                );
    }

    /**
     * 启动时在后台批量为所有记忆补齐 embedding（仅对尚未有向量的条目计算）。
     * 使用 Flux.flatMap 并发（最大 4 路），避免启动时阻塞 Spring 初始化。
     */
    private void buildEmbeddingsInBackground() {
        if (embeddingClient == null || embeddingStore == null) return;

        List<MemoryEntry> all = userMemories.values().stream()
                .flatMap(m -> m.values().stream())
                .collect(Collectors.toList());

        if (all.isEmpty()) return;

        log.info("Computing embeddings for {} existing memories in background...", all.size());

        Flux.fromIterable(all)
                .flatMap(entry -> {
                    String text = buildEmbeddingText(entry);
                    return embeddingClient.embed(text)
                            .subscribeOn(Schedulers.boundedElastic())
                            .doOnNext(vec -> embeddingStore.put(entry.getUserId(), entry.getId(), vec))
                            .thenReturn(entry.getName());
                }, /* concurrency */ 4)
                .subscribe(
                        name -> log.debug("Embedding ready: {}", name),
                        err  -> log.warn("Background embedding failed: {}", err.getMessage()),
                        ()   -> log.info("All memory embeddings computed.")
                );
    }

    /**
     * 构建 embedding 输入文本：拼接 name + description + content，截断至 MAX_EMBED_TEXT_LENGTH。
     * 多字段拼接能给模型提供更完整的语义上下文，提升检索准确率。
     */
    private String buildEmbeddingText(MemoryEntry entry) {
        StringBuilder sb = new StringBuilder();
        if (entry.getName() != null && !entry.getName().isBlank()) {
            sb.append(entry.getName()).append(". ");
        }
        if (entry.getDescription() != null && !entry.getDescription().isBlank()) {
            sb.append(entry.getDescription()).append(" ");
        }
        if (entry.getContent() != null) {
            sb.append(entry.getContent());
        }
        String text = sb.toString().trim();
        return text.length() > MAX_EMBED_TEXT_LENGTH
                ? text.substring(0, MAX_EMBED_TEXT_LENGTH)
                : text;
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
                ? sanitizeFileName(entry.getName()) + ".md"
                : entry.getId() + ".md";
            Path filePath = userDir.resolve(fileName);
            // 二次确认：防止 sanitize 后仍有路径逃逸
            if (!filePath.toAbsolutePath().normalize().startsWith(userDir.toAbsolutePath().normalize())) {
                log.warn("Skipping memory write: computed path escapes user directory: {}", fileName);
                return;
            }

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
                ? sanitizeFileName(entry.getName()) + ".md"
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
                if (line.length() > MEMORY_INDEX_MAX_LINE_LENGTH) {
                    line = line.substring(0, MEMORY_INDEX_TRUNCATE_LENGTH) + "...";
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

            String[] parts = content.split(FRONTMATTER_DELIMITER, FRONTMATTER_PARTS);
            if (parts.length >= FRONTMATTER_PARTS) {
                // 解析 YAML 格式的前置元数据
                String yaml = parts[1];
                for (String line : yaml.split("\n")) {
                    line = line.trim();
                    if (line.startsWith("name:")) {
                        name = line.substring(PREFIX_NAME).trim();
                    } else if (line.startsWith("description:")) {
                        description = line.substring(PREFIX_DESCRIPTION).trim();
                    } else if (line.startsWith("userId:")) {
                        // frontmatter 中明确记录的 userId 优先于目录名
                        String fmUserId = line.substring(PREFIX_USER_ID).trim();
                        if (!fmUserId.isEmpty()) userId = fmUserId;
                    } else if (line.startsWith("type:")) {
                        try {
                            type = MemoryType.valueOf(line.substring(PREFIX_TYPE).trim().toUpperCase());
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
     * 将 userId 转义为安全的目录名（去掉路径分隔符等特殊字符）。
     *
     * <p>不保留 "."：原实现保留点号，导致 userId=".." 或 "..." 之类输入
     * 在字符级过滤后原样存活，resolve() 将其解释为父目录跳转，
     * 使 persistMemoryToFile/deleteMemoryFile 逃逸到 memory 根目录之外
     * （典型输入 {@code {"userId": ".."}} 可造成任意路径写入/删除）。
     * 目录名本身不需要点号语义，直接整体替换为 "_" 即可消除该风险。
     */
    private String sanitizeDirName(String userId) {
        if (userId == null || userId.isBlank()) return "default";
        // 保留字母、数字、连字符、下划线；其余（含 "."）替换为 _，避免 ".."/"." 逃逸
        String safe = userId.replaceAll("[^a-zA-Z0-9\\-_]", "_").toLowerCase();
        return safe.isBlank() ? "default" : safe;
    }

    /**
     * 将记忆名称转义为安全的文件名（不含路径分隔符、空字节等危险字符）
     */
    private String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) return "unnamed";
        return name.replaceAll("[^a-zA-Z0-9\\-_.]", "_");
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
