package com.javacodeagent.core.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 记忆条目
 * <p>
 * 每条记忆存储在单独的文件中，格式如下：
 * <pre>
 * ---
 * name: short-kebab-case-slug
 * description: one-line summary
 * metadata:
 *   type: user|feedback|project|reference
 * ---
 * 记忆内容...
 *
 * 关联记忆: [[name1]], [[name2]]
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryEntry {
    private String id;
    private String userId;
    private MemoryType type;
    private String name;           // kebab-case slug
    private String description;    // one-line summary
    private String content;        // 记忆体内容
    private Map<String, Object> metadata;

    @Builder.Default
    private List<String> links = new ArrayList<>();  // [[name]] 跨引用

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
