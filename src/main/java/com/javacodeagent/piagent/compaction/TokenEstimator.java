package com.javacodeagent.piagent.compaction;

import com.javacodeagent.core.model.LLMResponse;
import com.javacodeagent.core.model.Message;
import com.javacodeagent.core.model.ToolCall;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 上下文 token 估算。
 *
 * <p>设计参照 pi 的 {@code estimateContextTokens}：采用**混合计数**而非纯启发式——
 * 以最后一条有效响应的真实 provider usage 为锚点，只对其后新增的消息做启发式估算。
 * 纯启发式的误差会随对话增长而累积，混合计数把误差限制在"上次调用之后"的增量部分。
 */
@Component
public class TokenEstimator {

    /** 英文文本约 4 字符 1 token；中文偏保守（实际约 1.5 字符/token，此处会低估）。 */
    private static final int CHARS_PER_TOKEN = 4;

    /** 单张图片的等效字符数，对齐 pi 的 ESTIMATED_IMAGE_CHARS。 */
    private static final int ESTIMATED_IMAGE_CHARS = 4800;

    /** 每条消息的固定开销（role 标记、分隔符等）。 */
    private static final int PER_MESSAGE_OVERHEAD_TOKENS = 4;

    /** CJK 字符的 token 密度高于拉丁字符，单独按此比率计算。 */
    private static final double CJK_CHARS_PER_TOKEN = 1.5;

    /**
     * 估算消息列表的总 token 数（无 usage 锚点的纯启发式路径）。
     */
    public int estimateMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Message m : messages) {
            total += estimateOne(m);
        }
        return total;
    }

    /**
     * 混合计数：以真实 usage 为锚点 + 其后消息的启发式估算。
     *
     * @param messages   完整消息列表
     * @param lastUsage  最后一次成功 LLM 调用返回的 usage（可为 null）
     * @param anchorIndex 该 usage 对应的消息下标；&lt; 0 表示无锚点
     */
    public int estimateWithAnchor(List<Message> messages,
                                  Map<String, Object> lastUsage,
                                  int anchorIndex) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        int anchorTokens = extractTotalTokens(lastUsage);
        if (anchorTokens <= 0 || anchorIndex < 0 || anchorIndex >= messages.size()) {
            return estimateMessages(messages);
        }

        int total = anchorTokens;
        for (int i = anchorIndex + 1; i < messages.size(); i++) {
            total += estimateOne(messages.get(i));
        }
        return total;
    }

    /**
     * 从 provider usage map 中提取总 token 数。
     *
     * <p>兼容两种字段命名：Anthropic 用 {@code input_tokens}/{@code output_tokens}，
     * OpenAI 用 {@code prompt_tokens}/{@code completion_tokens}，
     * 部分实现直接给 {@code total_tokens}。
     */
    public int extractTotalTokens(Map<String, Object> usage) {
        if (usage == null || usage.isEmpty()) {
            return 0;
        }
        Integer total = intValue(usage.get("total_tokens"));
        if (total != null && total > 0) {
            return total;
        }
        int input = orZero(intValue(usage.get("input_tokens")))
            + orZero(intValue(usage.get("prompt_tokens")));
        int output = orZero(intValue(usage.get("output_tokens")))
            + orZero(intValue(usage.get("completion_tokens")));
        return input + output;
    }

    /** 单条消息的 token 估算。 */
    public int estimateOne(Message m) {
        if (m == null) {
            return 0;
        }
        int tokens = PER_MESSAGE_OVERHEAD_TOKENS;

        if (m.getContent() != null) {
            tokens += estimateText(m.getContent());
        }

        // 工具调用的参数 JSON 也占 token
        if (m.getToolCalls() != null) {
            for (ToolCall tc : m.getToolCalls()) {
                tokens += estimateText(tc.getName());
                if (tc.getInput() != null) {
                    // 粗略按 JSON 序列化后的规模估算：每个键值对约 20 字符
                    tokens += (tc.getInput().size() * 20) / CHARS_PER_TOKEN;
                    for (Object v : tc.getInput().values()) {
                        if (v != null) {
                            tokens += estimateText(String.valueOf(v));
                        }
                    }
                }
            }
        }
        return tokens;
    }

    /**
     * 文本 token 估算，区分 CJK 与拉丁字符。
     *
     * <p>纯粹按 {@code chars/4} 会严重低估中文内容——一个汉字通常就是
     * 1 个 token 左右，而非 0.25 个。低估会导致压缩触发得太晚，
     * 请求真正发出去时才发现超限。
     */
    private int estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isCjk(cp)) {
                cjk++;
            } else {
                other++;
            }
            i += Character.charCount(cp);
        }
        return (int) Math.ceil(cjk / CJK_CHARS_PER_TOKEN)
            + (int) Math.ceil((double) other / CHARS_PER_TOKEN);
    }

    private boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)      // CJK 统一汉字
            || (cp >= 0x3400 && cp <= 0x4DBF)      // 扩展 A
            || (cp >= 0x3040 && cp <= 0x30FF)      // 日文假名
            || (cp >= 0xAC00 && cp <= 0xD7AF);     // 韩文
    }

    /** 单张图片的等效 token 数。 */
    public int estimateImage() {
        return ESTIMATED_IMAGE_CHARS / CHARS_PER_TOKEN;
    }

    private Integer intValue(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private int orZero(Integer i) {
        return i != null ? i : 0;
    }
}
