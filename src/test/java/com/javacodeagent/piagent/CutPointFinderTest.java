package com.javacodeagent.piagent;

import com.javacodeagent.core.enums.MessageType;
import com.javacodeagent.core.model.Message;
import com.javacodeagent.core.model.ToolCall;
import com.javacodeagent.piagent.compaction.CutPoint;
import com.javacodeagent.piagent.compaction.CutPointFinder;
import com.javacodeagent.piagent.compaction.TokenEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 压缩切点选择测试。
 *
 * <p>核心命题：切点<b>永远不能</b>落在 tool_use 与 tool_result 之间。
 * 旧的"保留最近 N 条"实现会产生这种切分，导致保留区出现没有对应调用的
 * 孤立 tool_result —— Anthropic API 会直接拒绝这种消息序列。
 */
class CutPointFinderTest {

    private CutPointFinder finder;
    private TokenEstimator estimator;

    @BeforeEach
    void setUp() {
        estimator = new TokenEstimator();
        finder = new CutPointFinder(estimator);
    }

    /**
     * 关键回归：切点落在 TOOL_RESULT 上时必须向后推移。
     *
     * <p>构造一段以 [assistant(tool_use), tool_result] 结尾的历史，
     * 并让保留预算恰好把切点压到 tool_result 上。
     */
    @Test
    void cutPointNeverSplitsToolCallFromResult() {
        List<Message> messages = new ArrayList<>();
        messages.add(user("first question"));
        for (int i = 0; i < 10; i++) {
            messages.add(assistantWithToolCall("call-" + i, "Read"));
            messages.add(toolResult("call-" + i, "file content ".repeat(50)));
        }

        // 预算很小，迫使切点尽量靠后
        CutPoint cut = finder.find(messages, 100);

        if (!cut.nothingToCompact()) {
            Message atCut = messages.get(cut.index());
            assertThat(atCut.getType())
                .as("cut point must not land on a TOOL_RESULT")
                .isNotEqualTo(MessageType.TOOL_RESULT);

            Message beforeCut = messages.get(cut.index() - 1);
            boolean prevHasToolCalls = beforeCut.getType() == MessageType.ASSISTANT
                && beforeCut.getToolCalls() != null
                && !beforeCut.getToolCalls().isEmpty();
            assertThat(prevHasToolCalls)
                .as("message before cut must not have unresolved tool calls")
                .isFalse();
        }
    }

    /** 保留区不应含有孤立的 tool_result（没有对应 tool_use 的）。 */
    @Test
    void retainedTailHasNoOrphanToolResults() {
        List<Message> messages = new ArrayList<>();
        messages.add(user("start"));
        for (int i = 0; i < 8; i++) {
            messages.add(assistantWithToolCall("c" + i, "Grep"));
            messages.add(toolResult("c" + i, "match ".repeat(80)));
            messages.add(user("follow-up " + i));
        }

        CutPoint cut = finder.find(messages, 200);
        if (cut.nothingToCompact()) {
            return;
        }

        List<Message> retained = messages.subList(cut.index(), messages.size());

        // 逐条扫描：每个 tool_result 之前必须能找到同批的 tool_use
        java.util.Set<String> seenToolCallIds = new java.util.HashSet<>();
        for (Message m : retained) {
            if (m.getType() == MessageType.ASSISTANT && m.getToolCalls() != null) {
                m.getToolCalls().forEach(tc -> seenToolCallIds.add(tc.getId()));
            }
            if (m.getType() == MessageType.TOOL_RESULT) {
                assertThat(seenToolCallIds)
                    .as("orphan tool_result in retained tail: %s", m.getToolCallId())
                    .contains(m.getToolCallId());
            }
        }
    }

    /** 消息总量在预算内时无需压缩。 */
    @Test
    void nothingToCompactWhenWithinBudget() {
        List<Message> messages = List.of(
            user("hi"),
            assistant("hello")
        );
        CutPoint cut = finder.find(messages, 100_000);
        assertThat(cut.nothingToCompact()).isTrue();
    }

    /** 空列表与单条消息应安全返回。 */
    @Test
    void handlesEmptyAndSingleMessage() {
        assertThat(finder.find(List.of(), 1000).nothingToCompact()).isTrue();
        assertThat(finder.find(List.of(user("only")), 1000).nothingToCompact()).isTrue();
        assertThat(finder.find(null, 1000).nothingToCompact()).isTrue();
    }

    /** 切点落在 USER 消息上时不应标记为 splitTurn。 */
    @Test
    void cutAtUserMessageIsNotSplitTurn() {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            messages.add(user("question " + i + " " + "x".repeat(100)));
            messages.add(assistant("answer " + i + " " + "y".repeat(100)));
        }

        CutPoint cut = finder.find(messages, 300);
        if (!cut.nothingToCompact()
                && messages.get(cut.index()).getType() == MessageType.USER) {
            assertThat(cut.splitTurn()).isFalse();
        }
    }

    /** 切点必须严格大于 0，否则等于"全部压缩"，保留区为空。 */
    @Test
    void cutIndexIsAlwaysPositive() {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            messages.add(user("m" + i + " " + "z".repeat(200)));
        }
        CutPoint cut = finder.find(messages, 50);
        if (!cut.nothingToCompact()) {
            assertThat(cut.index()).isGreaterThan(0);
            assertThat(cut.index()).isLessThan(messages.size());
        }
    }

    // ── TokenEstimator 相关 ──────────────────────────────────────────────────

    /** CJK 文本的 token 密度高于拉丁文本，估算应体现这一点。 */
    @Test
    void cjkTextEstimatedHigherThanLatinOfSameLength() {
        Message chinese = user("这是一段中文文本用于测试令牌估算");   // 16 chars
        Message english = user("abcdefghijklmnop");                  // 16 chars

        int cjkTokens = estimator.estimateOne(chinese);
        int latinTokens = estimator.estimateOne(english);

        assertThat(cjkTokens)
            .as("CJK should estimate higher than Latin for equal char count")
            .isGreaterThan(latinTokens);
    }

    /** usage 锚点存在时应以其为基准，只估算其后的增量。 */
    @Test
    void anchoredEstimateUsesRealUsage() {
        List<Message> messages = List.of(
            user("old 1"), assistant("old 2"), user("new 3")
        );
        Map<String, Object> usage = Map.of("input_tokens", 5000, "output_tokens", 1000);

        int anchored = estimator.estimateWithAnchor(messages, usage, 1);

        // 应当 ≥ 锚点值，且远大于纯启发式（这几条消息只有几十字符）
        assertThat(anchored).isGreaterThanOrEqualTo(6000);
    }

    /** 无锚点时退回纯启发式。 */
    @Test
    void fallsBackToHeuristicWithoutUsage() {
        List<Message> messages = List.of(user("hello world"));
        int withoutAnchor = estimator.estimateWithAnchor(messages, null, -1);
        int heuristic = estimator.estimateMessages(messages);
        assertThat(withoutAnchor).isEqualTo(heuristic);
    }

    /** 兼容 OpenAI 与 Anthropic 两种 usage 字段命名。 */
    @Test
    void extractsTokensFromBothProviderFormats() {
        assertThat(estimator.extractTotalTokens(
            Map.of("input_tokens", 100, "output_tokens", 50))).isEqualTo(150);
        assertThat(estimator.extractTotalTokens(
            Map.of("prompt_tokens", 200, "completion_tokens", 80))).isEqualTo(280);
        assertThat(estimator.extractTotalTokens(
            Map.of("total_tokens", 999))).isEqualTo(999);
        assertThat(estimator.extractTotalTokens(Map.of())).isZero();
        assertThat(estimator.extractTotalTokens(null)).isZero();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Message user(String content) {
        return Message.builder().type(MessageType.USER).content(content).build();
    }

    private Message assistant(String content) {
        return Message.builder().type(MessageType.ASSISTANT).content(content).build();
    }

    private Message assistantWithToolCall(String id, String toolName) {
        return Message.builder()
            .type(MessageType.ASSISTANT)
            .content("Let me use " + toolName)
            .toolCalls(List.of(ToolCall.builder()
                .id(id).name(toolName).input(Map.of("path", "/tmp/x")).build()))
            .build();
    }

    private Message toolResult(String toolCallId, String content) {
        return Message.builder()
            .type(MessageType.TOOL_RESULT)
            .toolCallId(toolCallId)
            .content(content)
            .build();
    }
}
