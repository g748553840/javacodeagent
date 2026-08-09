package com.javacodeagent.piagent.compaction;

import com.javacodeagent.core.enums.MessageType;
import com.javacodeagent.core.model.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 压缩切点选择器。
 *
 * <p>设计参照 pi 的 {@code findCutPoint} / {@code findValidCutPoints}：
 * 从尾部反向累加 token 直到达到保留预算，然后落到最近的**合法**切点。
 *
 * <p><b>为什么需要"合法切点"这个概念</b>：简单的"保留最近 N 条"会切在
 * ASSISTANT（含 tool_use）和 TOOL_RESULT 之间——摘要区留下了"我要调用工具 X"，
 * 保留区留下了孤立的工具结果。LLM 看到没有对应调用的结果会困惑，
 * Anthropic API 甚至会直接拒绝这种消息序列（tool_result 必须紧跟同批 tool_use）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CutPointFinder {

    private final TokenEstimator tokenEstimator;

    /**
     * 寻找压缩切点。
     *
     * @param messages         完整消息列表
     * @param keepRecentTokens 尾部保留的 token 预算
     * @return 切点；若无可压缩则 {@link CutPoint#nothingToCompact()}
     */
    public CutPoint find(List<Message> messages, int keepRecentTokens) {
        if (messages == null || messages.size() < 2) {
            return CutPoint.nothingToCompact();
        }

        // 从尾部反向累加，直到达到保留预算
        int acc = 0;
        int idx = messages.size();
        while (idx > 0 && acc < keepRecentTokens) {
            idx--;
            acc += tokenEstimator.estimateOne(messages.get(idx));
        }

        // 预算已能容纳全部消息 → 无需压缩
        if (idx <= 0) {
            return CutPoint.nothingToCompact();
        }

        // 向后寻找最近的合法切点（不能切在 TOOL_RESULT 上）
        int cut = idx;
        while (cut < messages.size() && !isValidCutPoint(messages, cut)) {
            cut++;
        }

        // 尾部全是 TOOL_RESULT，找不到合法切点
        if (cut >= messages.size()) {
            log.debug("No valid cut point found from index {} onward; skipping compaction", idx);
            return CutPoint.nothingToCompact();
        }

        // 切点落在轮次中间时，回溯到轮次起点，避免把一个 assistant 回复劈成两半
        boolean splitTurn = messages.get(cut).getType() != MessageType.USER;
        if (splitTurn) {
            int turnStart = findTurnStartIndex(messages, cut);
            if (turnStart > 0 && isValidCutPoint(messages, turnStart)) {
                cut = turnStart;
                splitTurn = messages.get(cut).getType() != MessageType.USER;
            }
        }

        if (cut <= 0) {
            return CutPoint.nothingToCompact();
        }

        log.debug("Cut point at index {}/{} (splitTurn={}), retained ~{} tokens",
            cut, messages.size(), splitTurn, acc);
        return CutPoint.of(cut, splitTurn);
    }

    /**
     * 判定下标 i 是否为合法切点。
     *
     * <p>不合法的情形：
     * <ul>
     *   <li>{@code messages[i]} 是 TOOL_RESULT —— 会与前面的 tool_use 分离</li>
     *   <li>{@code messages[i-1]} 是带 tool_use 的 ASSISTANT —— 同上，从另一侧看</li>
     * </ul>
     */
    private boolean isValidCutPoint(List<Message> messages, int i) {
        if (i <= 0 || i >= messages.size()) {
            return false;
        }
        if (messages.get(i).getType() == MessageType.TOOL_RESULT) {
            return false;
        }
        Message prev = messages.get(i - 1);
        boolean prevHasToolCalls = prev.getType() == MessageType.ASSISTANT
            && prev.getToolCalls() != null
            && !prev.getToolCalls().isEmpty();
        return !prevHasToolCalls;
    }

    /**
     * 从下标 i 回溯到所属轮次的起点（最近的 USER 消息下标）。
     *
     * @return 轮次起点下标；找不到则返回 i 本身
     */
    private int findTurnStartIndex(List<Message> messages, int i) {
        for (int j = i; j > 0; j--) {
            if (messages.get(j).getType() == MessageType.USER) {
                return j;
            }
        }
        return i;
    }
}
