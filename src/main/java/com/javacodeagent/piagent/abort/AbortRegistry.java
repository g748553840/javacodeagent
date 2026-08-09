package com.javacodeagent.piagent.abort;

import com.javacodeagent.piagent.tool.AbortSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按会话维护在途的 {@link AbortSignal}。
 *
 * <p>{@code AbortSignal} 本身只是一个协作式开关；要让它真正可用，必须有人能在
 * 「对话正在执行工具」的那一刻拿到它。发起中止的是另一个 HTTP 请求
 * （用户点了停止按钮），它与对话所在的响应式链路完全不在一个调用栈上，
 * 因此需要这样一个以 conversationId 为键的中间登记处。
 *
 * <p><b>生命周期必须成对</b>：对话开始时 {@link #register}，结束时
 * {@link #release}。漏掉 release 会让 map 无限增长——这是个会缓慢泄漏的
 * 长生命周期单例，调用方应当把 release 放在 {@code doFinally} 里，
 * 保证正常完成、报错、被取消三条路径都会清理。
 */
@Slf4j
@Component
public class AbortRegistry {

    private final Map<String, AbortSignal> signals = new ConcurrentHashMap<>();

    /**
     * 为会话登记一个新的中止信号并返回。
     *
     * <p>同一会话重复 register（例如用户在上一轮还没结束时又发了一条消息）
     * 会覆盖旧信号。这里刻意<b>不</b>中止旧信号：旧链路是否还该继续执行
     * 是对话层的语义决策，登记处不替它做主。
     */
    public AbortSignal register(String conversationId) {
        AbortSignal signal = new AbortSignal();
        if (conversationId != null) {
            signals.put(conversationId, signal);
        }
        return signal;
    }

    /**
     * 取会话当前的中止信号。
     *
     * <p>未登记时返回 {@link AbortSignal#NEVER} 而不是 null——调用方几乎总是
     * 要把它往下传给工具，返回一个永不中止的哨兵比让每个调用点判空更不容易出错。
     */
    public AbortSignal signalFor(String conversationId) {
        if (conversationId == null) {
            return AbortSignal.NEVER;
        }
        return signals.getOrDefault(conversationId, AbortSignal.NEVER);
    }

    /**
     * 中止指定会话。
     *
     * @return 是否确实找到了在途会话。false 表示该会话不在执行中
     *         （已结束或从未开始），调用方可据此返回 404
     */
    public boolean abort(String conversationId, String reason) {
        AbortSignal signal = conversationId != null ? signals.get(conversationId) : null;
        if (signal == null) {
            return false;
        }
        log.info("Aborting conversation {}: {}", conversationId, reason);
        signal.abort(reason);
        return true;
    }

    /** 释放会话的中止信号。幂等。 */
    public void release(String conversationId) {
        if (conversationId != null) {
            signals.remove(conversationId);
        }
    }

    /** 当前在途会话数，供监控与测试使用。 */
    public int activeCount() {
        return signals.size();
    }
}
