package com.javacodeagent.piagent.tool;

import com.javacodeagent.core.model.ToolCall;
import com.javacodeagent.core.model.ToolExecutionResult;

import java.util.Map;

/**
 * 一批工具执行期间的生命周期观察者。
 *
 * <p>存在的理由是解耦：{@link com.javacodeagent.core.tool.ToolManager} 需要在工具
 * 开始/进行中/结束时通知外界，但它不该知道「外界」是 SSE、WebSocket 还是日志。
 * 观察者把这三个时点抽象出来，由
 * {@link com.javacodeagent.core.conversation.ConversationManager} 决定翻译成什么事件。
 *
 * <p><b>线程模型</b>：并行执行时 {@link #onStart} / {@link #onUpdate} /
 * {@link #onComplete} 会被多个线程并发调用，实现方必须自己保证线程安全。
 * 典型实现是把事件推进一个 {@code Sinks.Many}（其 {@code tryEmitNext} 是线程安全的）。
 *
 * <p>三个方法都是 {@code default} 空实现，只关心其中一个时点的调用方无需写空方法。
 */
public interface ToolBatchObserver {

    /**
     * 工具即将开始执行。
     *
     * <p>在准备阶段（权限、钩子）通过之后调用——被权限拒绝或钩子拦截的工具
     * 不会收到 {@code onStart}，但仍会收到 {@link #onComplete}（携带失败结果）。
     * 这样「开始了但没结束」的状态不会出现在 UI 上。
     */
    default void onStart(ToolCall call) {
    }

    /**
     * 工具执行过程中的增量输出。
     *
     * @param partial 见 {@link ToolUpdateCallback#update(Map)} 的键名约定
     */
    default void onUpdate(ToolCall call, Map<String, Object> partial) {
    }

    /** 工具执行结束（成功、失败、被拒绝、被中止都会调用）。 */
    default void onComplete(ToolCall call, ToolExecutionResult result) {
    }

    /** 空实现，供不关心执行过程的调用方使用（如非流式对话）。 */
    ToolBatchObserver NOOP = new ToolBatchObserver() {
    };
}
