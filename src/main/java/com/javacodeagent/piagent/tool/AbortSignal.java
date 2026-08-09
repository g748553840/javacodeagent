package com.javacodeagent.piagent.tool;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 协作式中止信号。
 *
 * <p>对应 pi 的 {@code AbortSignal}（Web 标准 AbortController 的一部分）。
 * Java 没有等价原语，此处手写。
 *
 * <p><b>为什么是协作式而非强制中断</b>：
 * {@code Thread.interrupt()} 只能中断处于 {@code wait}/{@code sleep}/{@code join}
 * 等可中断状态的线程，对 CPU 密集的循环或阻塞式 {@code InputStream.read()} 无效；
 * {@code Thread.stop()} 已废弃且会在任意字节码位置抛异常，导致对象处于不一致状态。
 * 协作式要求长任务主动检查 {@link #throwIfAborted()} 或注册 {@link #onAbort}，
 * 代价是需要工具作者配合，换来的是中止点的状态完全可控。
 *
 * <p>线程安全：所有方法均可从任意线程调用。
 */
@Slf4j
public final class AbortSignal {

    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile String reason;

    /** 一个永不中止的信号，供不关心中止的调用方复用。 */
    public static final AbortSignal NEVER = new AbortSignal();

    public boolean isAborted() {
        return aborted.get();
    }

    public String getReason() {
        return reason;
    }

    /**
     * 若已中止则抛出 {@link AbortedException}。
     *
     * <p>长任务应在循环体内周期性调用，例如逐行读取子进程输出时每读一行检查一次。
     */
    public void throwIfAborted() {
        if (aborted.get()) {
            throw new AbortedException(reason != null ? reason : "Operation aborted");
        }
    }

    /**
     * 注册中止回调。若信号已中止，回调立即在当前线程执行。
     *
     * <p>典型用途：销毁子进程、关闭连接。回调中的异常会被捕获并记录，
     * 不会影响其他回调的执行——一个清理动作失败不应阻止其余清理。
     */
    public void onAbort(Runnable listener) {
        if (listener == null) {
            return;
        }
        if (aborted.get()) {
            runSafely(listener);
            return;
        }
        listeners.add(listener);
        // 双重检查：注册与中止可能并发，避免回调丢失
        if (aborted.get() && listeners.remove(listener)) {
            runSafely(listener);
        }
    }

    /** 触发中止。幂等——重复调用只有首次生效。 */
    public void abort() {
        abort("Operation aborted");
    }

    public void abort(String reason) {
        if (aborted.compareAndSet(false, true)) {
            this.reason = reason;
            for (Runnable listener : listeners) {
                runSafely(listener);
            }
            listeners.clear();
        }
    }

    private void runSafely(Runnable listener) {
        try {
            listener.run();
        } catch (Exception e) {
            log.warn("Abort listener threw an exception", e);
        }
    }
}
