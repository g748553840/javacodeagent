package com.javacodeagent.core.conversation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationRequest {
    private String conversationId;
    private String content;
    private Path workingDirectory;
    /**
     * 发起请求的用户 ID。
     * 由接入层（ConversationWebSocketHandler / ConversationController）
     * 从请求头 X-User-Id 提取并填入；未提供时默认为 "default"。
     */
    private String userId;
}