package com.javacodeagent.core.model;

import com.javacodeagent.core.enums.PermissionLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationContext {
    private String conversationId;
    /** 发起本次对话的用户 ID，由接入层从 X-User-Id 请求头注入，默认 "default" */
    private String userId;
    private List<Message> messages;
    private List<ToolDefinition> availableTools;
    private PermissionLevel permissionLevel;
    private Path workingDirectory;
    private Map<String, Object> metadata;
}