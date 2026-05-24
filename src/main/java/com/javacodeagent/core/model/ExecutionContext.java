package com.javacodeagent.core.model;

import com.javacodeagent.core.enums.PermissionLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionContext {
    private String userId;
    private String conversationId;
    private PermissionLevel permissionLevel;
    private Path workingDirectory;
    private Map<String, Object> metadata;
}