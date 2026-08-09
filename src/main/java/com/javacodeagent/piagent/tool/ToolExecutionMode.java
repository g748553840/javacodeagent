package com.javacodeagent.piagent.tool;

/**
 * 工具执行模式。
 *
 * <p>对应 pi 的 {@code ToolExecutionMode}。一批工具调用默认可以并行执行以降低延迟，
 * 但某些工具必须串行——典型的是会修改共享状态的操作（{@code git commit}、
 * 写同一个文件、递增计数器）。并行执行这类工具会产生竞态。
 *
 * <p>粒度设计：全局策略配一个默认值，单个工具通过
 * {@link com.javacodeagent.core.tool.Tool#getExecutionMode()} 覆盖。
 * 返回 {@code null} 表示继承全局策略。
 *
 * <p>注意 pi 的批次规则：一批调用中只要**有任意一个**工具声明 SEQUENTIAL，
 * 整批都退化为串行。这比"部分并行部分串行"简单得多，也避免了
 * 「串行工具与并行工具之间是否需要屏障」这个难以推理的问题。
 */
public enum ToolExecutionMode {

    /** 可与同批其他工具并行执行。 */
    PARALLEL,

    /** 必须串行执行；同批存在此模式的工具时整批串行。 */
    SEQUENTIAL
}
