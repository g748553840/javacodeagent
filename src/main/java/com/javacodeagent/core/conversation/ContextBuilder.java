package com.javacodeagent.core.conversation;

import com.javacodeagent.core.enums.MessageType;
import com.javacodeagent.core.model.ConversationContext;
import com.javacodeagent.core.model.Message;
import com.javacodeagent.core.model.ToolDefinition;
import com.javacodeagent.core.tool.ToolManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 上下文构建器
 * 将用户请求转换为 LLM 所需的对话上下文
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextBuilder {

    private final ToolManager toolManager;

    /**
     * 从用户请求构建对话上下文
     *
     * @param request 用户请求
     * @param existingMessages 现有消息历史（可选）
     * @return 构建好的对话上下文
     */
    public ConversationContext build(ConversationRequest request, List<Message> existingMessages) {
        List<Message> messages = new ArrayList<>();
        if (existingMessages != null) {
            messages.addAll(existingMessages);
        }

        // 添加用户消息
        messages.add(Message.builder()
            .type(MessageType.USER)
            .content(request.getContent())
            .build());

        List<ToolDefinition> availableTools = toolManager.getAllToolDefinitions();
        Path workingDir = request.getWorkingDirectory() != null
            ? request.getWorkingDirectory()
            : Paths.get(".").toAbsolutePath().normalize();

        return ConversationContext.builder()
            .conversationId(request.getConversationId())
            .messages(messages)
            .availableTools(availableTools)
            .workingDirectory(workingDir)
            .build();
    }

    /**
     * 构建包含工具调用结果的上下文（后续轮次）
     */
    public ConversationContext buildWithResults(
            ConversationContext previousContext,
            List<Message> newMessages) {

        List<Message> allMessages = new ArrayList<>(previousContext.getMessages());
        allMessages.addAll(newMessages);

        return ConversationContext.builder()
            .conversationId(previousContext.getConversationId())
            .messages(allMessages)
            .availableTools(previousContext.getAvailableTools())
            .permissionLevel(previousContext.getPermissionLevel())
            .workingDirectory(previousContext.getWorkingDirectory())
            .metadata(previousContext.getMetadata())
            .build();
    }
}
