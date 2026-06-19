package com.javacodeagent.core.conversation;

import com.javacodeagent.config.ContextCompressionConfig;
import com.javacodeagent.core.enums.MessageType;
import com.javacodeagent.core.llm.LLMClient;
import com.javacodeagent.core.model.ConversationContext;
import com.javacodeagent.core.model.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Context compressor that uses the LLM to generate semantic summaries of old messages,
 * reducing token usage while preserving important context.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextCompressor {

    private final LLMClient llmClient;
    private final ContextCompressionConfig compressionConfig;

    /**
     * Compresses the conversation context when message count exceeds the threshold.
     * Uses LLM summarization for accurate semantic compression.
     */
    public Mono<ConversationContext> compress(ConversationContext context) {
        if (!compressionConfig.isEnabled()
                || context.getMessages() == null
                || context.getMessages().size() <= compressionConfig.getThreshold()) {
            return Mono.just(context);
        }

        List<Message> messages = context.getMessages();
        int keepRecent = compressionConfig.getKeepRecent();
        List<Message> toCompress = new ArrayList<>(
            messages.subList(0, messages.size() - keepRecent));
        List<Message> toKeep = new ArrayList<>(
            messages.subList(messages.size() - keepRecent, messages.size()));

        log.info("Compressing context: {} messages -> LLM summary + {} recent",
            messages.size(), toKeep.size());

        return buildLLMSummary(toCompress, context)
            .map(summary -> buildCompressedContext(context, summary, toKeep))
            .onErrorResume(e -> {
                log.warn("LLM summarization failed, using fallback string summary", e);
                String fallbackSummary = buildFallbackSummary(toCompress);
                return Mono.just(buildCompressedContext(context, fallbackSummary, toKeep));
            });
    }

    private Mono<String> buildLLMSummary(List<Message> messages, ConversationContext originalContext) {
        String conversationText = formatMessagesForSummary(messages);

        String summarizationPrompt =
            "Summarize the following conversation history concisely. Focus on:\n" +
            "1. The user's primary goals and requests\n" +
            "2. Tools executed and their key results\n" +
            "3. Important decisions or findings\n" +
            "4. Current state of any in-progress tasks\n\n" +
            "Keep the summary under 400 words. Be specific about file paths, " +
            "function names, and error messages mentioned.\n\n" +
            "Conversation to summarize:\n" + conversationText;

        ConversationContext summarizationContext = ConversationContext.builder()
            .conversationId("summary-" + UUID.randomUUID())
            .messages(List.of(
                Message.builder()
                    .type(MessageType.USER)
                    .content(summarizationPrompt)
                    .build()
            ))
            .workingDirectory(originalContext.getWorkingDirectory())
            .build();

        return llmClient.chat(summarizationContext)
            .map(response -> response.getContent() != null ? response.getContent() : "")
            .filter(s -> !s.isEmpty())
            .switchIfEmpty(Mono.just(buildFallbackSummary(messages)));
    }

    private String formatMessagesForSummary(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            if (msg.getContent() == null || msg.getContent().isEmpty()) {
                continue;
            }
            String role = msg.getType().name();
            String content = msg.getContent().length() > 500
                ? msg.getContent().substring(0, 500) + "...[truncated]"
                : msg.getContent();

            if (msg.getType() == MessageType.TOOL_RESULT) {
                content = "[Tool Result id=" + msg.getToolCallId() + "] " + content;
            }
            sb.append(role).append(": ").append(content).append("\n\n");
        }
        return sb.toString();
    }

    private String buildFallbackSummary(List<Message> messages) {
        StringBuilder summary = new StringBuilder("Earlier conversation: ");
        int charBudget = 1500;

        for (Message msg : messages) {
            if (msg.getContent() == null || msg.getContent().isEmpty()) continue;
            if (msg.getType() == MessageType.SYSTEM) continue;

            String entry;
            if (msg.getType() == MessageType.USER) {
                entry = "[User] " + truncate(msg.getContent(), 200);
            } else if (msg.getType() == MessageType.ASSISTANT) {
                entry = "[Assistant] " + truncate(msg.getContent(), 150);
            } else if (msg.getType() == MessageType.TOOL_RESULT) {
                entry = "[ToolResult] " + truncate(msg.getContent(), 100);
            } else {
                continue;
            }

            if (summary.length() + entry.length() > charBudget) {
                summary.append("...[earlier messages omitted]");
                break;
            }
            summary.append(entry).append("; ");
        }
        return summary.toString();
    }

    private ConversationContext buildCompressedContext(
            ConversationContext original, String summary, List<Message> recentMessages) {

        Message summaryMessage = Message.builder()
            .type(MessageType.SYSTEM)
            .content("[Compressed conversation summary]: " + summary)
            .build();

        List<Message> compressed = new ArrayList<>();
        compressed.add(summaryMessage);
        compressed.addAll(recentMessages);

        return ConversationContext.builder()
            .conversationId(original.getConversationId())
            .userId(original.getUserId())   // 压缩后保持用户身份不变
            .messages(compressed)
            .availableTools(original.getAvailableTools())
            .permissionLevel(original.getPermissionLevel())
            .workingDirectory(original.getWorkingDirectory())
            .metadata(original.getMetadata())
            .build();
    }

    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }
}
