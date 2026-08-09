package com.javacodeagent.core.conversation;

import com.javacodeagent.config.ContextCompressionConfig;
import com.javacodeagent.core.enums.MessageType;
import com.javacodeagent.core.llm.LLMClient;
import com.javacodeagent.core.model.ConversationContext;
import com.javacodeagent.core.model.Message;
import com.javacodeagent.piagent.compaction.CutPoint;
import com.javacodeagent.piagent.compaction.CutPointFinder;
import com.javacodeagent.piagent.compaction.TokenEstimator;
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
 *
 * <p>支持两种切分策略：
 * <ul>
 *   <li><b>token 模式</b>（默认）：按 token 预算触发，用 {@link CutPointFinder}
 *       选择合法切点，保证不切开 tool_use / tool_result 配对</li>
 *   <li><b>条数模式</b>：按消息条数触发并保留最近 N 条（旧行为，配置
 *       {@code context.compression.token-based=false} 启用）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextCompressor {

    private static final int FORMAT_SUMMARY_MAX_CONTENT_LENGTH = 500;
    private static final int FALLBACK_SUMMARY_CHAR_BUDGET = 1500;
    private static final int TRUNCATE_USER_MSG = 200;
    private static final int TRUNCATE_ASSISTANT_MSG = 150;
    private static final int TRUNCATE_TOOL_RESULT_MSG = 100;

    private final LLMClient llmClient;
    private final ContextCompressionConfig compressionConfig;
    private final CutPointFinder cutPointFinder;
    private final TokenEstimator tokenEstimator;

    /**
     * Compresses the conversation context when it exceeds the configured budget.
     * Uses LLM summarization for accurate semantic compression.
     */
    public Mono<ConversationContext> compress(ConversationContext context) {
        if (!compressionConfig.isEnabled()
                || context.getMessages() == null
                || context.getMessages().isEmpty()) {
            return Mono.just(context);
        }

        List<Message> messages = context.getMessages();
        Split split = compressionConfig.isTokenBased()
            ? splitByTokenBudget(messages)
            : splitByMessageCount(messages);

        if (split == null) {
            return Mono.just(context);
        }

        log.info("Compressing context: {} messages -> LLM summary + {} recent ({} mode)",
            messages.size(), split.toKeep().size(),
            compressionConfig.isTokenBased() ? "token" : "count");

        return buildLLMSummary(split.toCompress(), context)
            .map(summary -> buildCompressedContext(context, summary, split.toKeep()))
            .onErrorResume(e -> {
                log.warn("LLM summarization failed, using fallback string summary", e);
                String fallbackSummary = buildFallbackSummary(split.toCompress());
                return Mono.just(buildCompressedContext(context, fallbackSummary, split.toKeep()));
            });
    }

    /** 切分结果：前半段被摘要，后半段保留原文。 */
    private record Split(List<Message> toCompress, List<Message> toKeep) {}

    /**
     * token 预算模式：超过 {@code maxTokens - reserveTokens} 时触发，
     * 由 {@link CutPointFinder} 选择不破坏工具调用配对的切点。
     */
    private Split splitByTokenBudget(List<Message> messages) {
        int estimated = tokenEstimator.estimateMessages(messages);
        int budget = compressionConfig.getMaxTokens() - compressionConfig.getReserveTokens();

        if (estimated <= budget) {
            return null;
        }

        CutPoint cut = cutPointFinder.find(messages, compressionConfig.getKeepRecentTokens());
        if (cut.nothingToCompact()) {
            log.debug("Context over budget ({} > {}) but no valid cut point; skipping compaction",
                estimated, budget);
            return null;
        }

        log.debug("Token budget exceeded: {} > {}, cutting at index {} (splitTurn={})",
            estimated, budget, cut.index(), cut.splitTurn());

        return new Split(
            new ArrayList<>(messages.subList(0, cut.index())),
            new ArrayList<>(messages.subList(cut.index(), messages.size()))
        );
    }

    /**
     * 消息条数模式（旧行为）。
     *
     * <p>即便在此模式下也会对切点做合法性修正——原实现直接按
     * {@code size - keepRecent} 切分，可能把 tool_use 留在摘要区
     * 而 tool_result 留在保留区，导致 Anthropic API 拒绝该消息序列。
     */
    private Split splitByMessageCount(List<Message> messages) {
        if (messages.size() <= compressionConfig.getThreshold()) {
            return null;
        }
        int keepRecent = Math.min(compressionConfig.getKeepRecent(), messages.size() - 1);
        if (keepRecent < 0) {
            return null;
        }

        int cutIndex = adjustCutIndexForToolPairing(messages, messages.size() - keepRecent);
        if (cutIndex <= 0 || cutIndex >= messages.size()) {
            return null;
        }

        return new Split(
            new ArrayList<>(messages.subList(0, cutIndex)),
            new ArrayList<>(messages.subList(cutIndex, messages.size()))
        );
    }

    /**
     * 向后推移切点直到不破坏 tool_use / tool_result 配对。
     *
     * <p>返回值可能等于 {@code messages.size()}（尾部全是工具结果），
     * 调用方需检查边界。
     */
    private int adjustCutIndexForToolPairing(List<Message> messages, int cutIndex) {
        int i = Math.max(1, cutIndex);
        while (i < messages.size()) {
            if (messages.get(i).getType() == MessageType.TOOL_RESULT) {
                i++;
                continue;
            }
            Message prev = messages.get(i - 1);
            boolean prevHasToolCalls = prev.getType() == MessageType.ASSISTANT
                && prev.getToolCalls() != null
                && !prev.getToolCalls().isEmpty();
            if (prevHasToolCalls) {
                i++;
                continue;
            }
            return i;
        }
        return i;
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
            .availableTools(List.of())
            .workingDirectory(originalContext.getWorkingDirectory())
            .permissionLevel(originalContext.getPermissionLevel())
            .build();

        return llmClient.chat(summarizationContext)
            // LLM 客户端现在以 error 标记表达失败而非抛异常，
            // 这里必须显式检查，否则失败响应的空 content 会被当作合法摘要。
            .filter(response -> {
                if (response.isError()) {
                    log.warn("Summarization LLM call failed: {}", response.getErrorMessage());
                    return false;
                }
                return true;
            })
            .map(response -> response.getContent() != null ? response.getContent() : "")
            .filter(s -> !s.isBlank())
            .switchIfEmpty(Mono.fromSupplier(() -> buildFallbackSummary(messages)));
    }

    private String formatMessagesForSummary(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            if (msg.getContent() == null || msg.getContent().isEmpty()) {
                continue;
            }
            String role = msg.getType().name();
            String content = msg.getContent().length() > FORMAT_SUMMARY_MAX_CONTENT_LENGTH
                ? msg.getContent().substring(0, FORMAT_SUMMARY_MAX_CONTENT_LENGTH) + "...[truncated]"
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
        int charBudget = FALLBACK_SUMMARY_CHAR_BUDGET;

        for (Message msg : messages) {
            if (msg.getContent() == null || msg.getContent().isEmpty()) continue;
            if (msg.getType() == MessageType.SYSTEM) continue;

            String entry;
            if (msg.getType() == MessageType.USER) {
                entry = "[User] " + truncate(msg.getContent(), TRUNCATE_USER_MSG);
            } else if (msg.getType() == MessageType.ASSISTANT) {
                entry = "[Assistant] " + truncate(msg.getContent(), TRUNCATE_ASSISTANT_MSG);
            } else if (msg.getType() == MessageType.TOOL_RESULT) {
                entry = "[ToolResult] " + truncate(msg.getContent(), TRUNCATE_TOOL_RESULT_MSG);
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
