package com.javacodeagent.piagent.tool;

import java.util.Map;

/**
 * 工具执行期间的流式进度回调。
 *
 * <p>对应 pi 的 {@code AgentToolUpdateCallback}。长时间运行的工具
 * （Bash 命令、大文件处理）通过它把中间输出推给 UI，
 * 而不必等到执行结束才一次性返回。
 *
 * <p>实现方需注意：此回调可能被高频调用（如每行输出一次），
 * 不应在其中做阻塞 IO 或重量级计算。
 */
@FunctionalInterface
public interface ToolUpdateCallback {

    /**
     * 推送一次增量更新。
     *
     * @param partial 增量数据。约定键名：
     *                {@code output} — 文本增量；
     *                {@code progress} — 0-1 之间的完成度；
     *                {@code stage} — 当前阶段描述
     */
    void update(Map<String, Object> partial);

    /** 空实现，供不需要流式的调用方使用。 */
    ToolUpdateCallback NOOP = partial -> { };
}
