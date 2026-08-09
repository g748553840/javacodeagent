package com.javacodeagent.piagent.compaction;

/**
 * 压缩切点。
 *
 * @param index       切点下标：{@code [0, index)} 被摘要，{@code [index, size)} 保留原文
 * @param splitTurn   切点是否落在一个对话轮次的中间（非 USER 消息边界）
 * @param nothingToCompact 是否无可压缩（消息太少或找不到合法切点）
 */
public record CutPoint(int index, boolean splitTurn, boolean nothingToCompact) {

    public static CutPoint of(int index, boolean splitTurn) {
        return new CutPoint(index, splitTurn, false);
    }

    public static CutPoint nothingToCompact() {
        return new CutPoint(-1, false, true);
    }
}
