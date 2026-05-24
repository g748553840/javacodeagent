package com.javacodeagent.core.conversation;

import com.javacodeagent.core.enums.MessageType;
import com.javacodeagent.core.model.ConversationContext;
import com.javacodeagent.core.model.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 上下文压缩器
 * 当消息历史接近 token 限制时，将早期非关键消息压缩为摘要
 */
@Slf4j
@Component
public class ContextCompressor {

    /**
     * 触发压缩的消息数阈值
     */
    private static final int COMPRESSION_THRESHOLD = 40;

    /**
     * 压缩后保留的最近消息数
     */
    private static final int KEEP_RECENT_MESSAGES = 10;

    /**
     * 最大摘要长度
     */
    private static final int MAX_SUMMARY_LENGTH = 2000;

    /**
     * 压缩对话上下文
     * 策略：将最早的非关键消息智能压缩为摘要
     *
     * @param context 原始上下文
     * @return 压缩后的上下文（如果未达到阈值，返回原上下文）
     */
    public ConversationContext compress(ConversationContext context) {
        if (context.getMessages() == null || context.getMessages().size() <= COMPRESSION_THRESHOLD) {
            return context;
        }

        List<Message> messages = context.getMessages();
        List<Message> toCompress = messages.subList(0, messages.size() - KEEP_RECENT_MESSAGES);
        List<Message> keep = new ArrayList<>(messages.subList(
            messages.size() - KEEP_RECENT_MESSAGES,
            messages.size()
        ));

        // 构建摘要
        String summary = buildSummary(toCompress);

        // 创建摘要消息
        Message summaryMessage = Message.builder()
            .type(MessageType.SYSTEM)
            .content("[Compressed Summary of earlier conversation]: " + summary)
            .build();

        List<Message> compressed = new ArrayList<>();
        compressed.add(summaryMessage);
        compressed.addAll(keep);

        log.info("Context compressed: {} messages -> 1 summary + {} recent messages",
            messages.size(), keep.size());

        return ConversationContext.builder()
            .conversationId(context.getConversationId())
            .messages(compressed)
            .availableTools(context.getAvailableTools())
            .permissionLevel(context.getPermissionLevel())
            .workingDirectory(context.getWorkingDirectory())
            .metadata(context.getMetadata())
            .build();
    }

    /**
     * 构建消息摘要
     * 提取关键信息：用户意图、工具调用结果、重要决定
     */
    private String buildSummary(List<Message> messages) {
        StringBuilder summary = new StringBuilder();

        for (Message msg : messages) {
            String content = extractKeyContent(msg);
            if (content != null && !content.isEmpty()) {
                if (summary.length() + content.length() > MAX_SUMMARY_LENGTH) {
                    summary.append("...[truncated]");
                    break;
                }
                summary.append("[").append(msg.getType()).append("] ").append(content).append("; ");
            }
        }

        return summary.toString();
    }

    /**
     * 提取消息中的关键内容
     * 过滤掉过长的工具调用结果和无意义消息
     */
    private String extractKeyContent(Message msg) {
        if (msg.getContent() == null || msg.getContent().isEmpty()) {
            return null;
        }

        // 只提取有意义的内容（长度 > 10 且不是纯工具输出）
        if (msg.getType() == MessageType.USER && msg.getContent().length() > 10) {
            return truncateContent(msg.getContent(), 200);
        }

        if (msg.getType() == MessageType.ASSISTANT && msg.getContent().length() > 10) {
            // 只保留助手响应的前 150 个字符
            return truncateContent(msg.getContent(), 150);
        }

        if (msg.getType() == MessageType.TOOL_RESULT) {
            // 工具结果只保留成功/失败状态和简短的摘要
            if (msg.getContent() != null && msg.getContent().length() < 200) {
                return "[Tool Result] " + truncateContent(msg.getContent(), 100);
            }
            // 过长的工具结果用占位符
            return "[Tool Result] (length: " + msg.getContent().length() + " chars)";
        }

        return null;
    }

    private String truncateContent(String content, int maxLen) {
        if (content == null || content.length() <= maxLen) {
            return content;
        }
        return content.substring(0, maxLen) + "...";
    }
}
