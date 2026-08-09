package com.javacodeagent.piagent.tool;

/**
 * 协作式中止异常。
 *
 * <p>由 {@link AbortSignal#throwIfAborted()} 抛出，工具实现应让其向外传播，
 * 由调用方转换为"已中止"的执行结果。继承 {@link RuntimeException} 以免
 * 污染所有工具方法的 throws 声明。
 */
public class AbortedException extends RuntimeException {

    public AbortedException(String message) {
        super(message);
    }
}
