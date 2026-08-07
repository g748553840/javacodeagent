package com.javacodeagent.core.data;

import com.javacodeagent.core.data.model.DataQueryResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * SQL 执行服务。
 *
 * <p>功能增强：
 * <ul>
 *   <li><b>Micrometer 指标</b>：每次 SQL 执行记录 {@code data_agent_query_duration_seconds}
 *       Timer，通过 {@code /actuator/metrics/data_agent_query_duration_seconds} 可读。</li>
 *   <li><b>慢查询日志</b>：执行时间超过 {@code data-agent.slow-query-threshold-ms}（默认 2000ms）
 *       时，以 WARN 级别记录 SQL 及耗时，便于运维排查。</li>
 *   <li><b>执行超时</b>：通过 {@code PreparedStatement.setQueryTimeout()} 在 DB 驱动层强制超时。</li>
 * </ul>
 */
@Slf4j
@Service
public class SqlExecutor {

    private static final String TIMER_NAME = "data_agent_query_duration_seconds";
    private static final String TAG_STATUS  = "status";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_ERROR   = "error";

    private final DataSourceConnector connector;

    /** Micrometer 注册表（可选：无 Actuator 时为 null，优雅降级）。 */
    private final MeterRegistry meterRegistry;

    /** 慢查询阈值（毫秒）；超过此值记录 WARN 日志。 */
    private final long slowQueryThresholdMs;

    public SqlExecutor(DataSourceConnector connector,
                       @Autowired(required = false) MeterRegistry meterRegistry,
                       @Value("${data-agent.slow-query-threshold-ms:2000}") long slowQueryThresholdMs) {
        this.connector = connector;
        this.meterRegistry = meterRegistry;
        this.slowQueryThresholdMs = slowQueryThresholdMs;
        if (meterRegistry != null) {
            log.info("SqlExecutor: Micrometer metrics enabled (timer={})", TIMER_NAME);
        }
    }

    public Mono<DataQueryResult> execute(String sql) {
        return execute(sql, DataAgentConstants.DEFAULT_MAX_ROWS, DataAgentConstants.DEFAULT_QUERY_TIMEOUT);
    }

    /**
     * 执行 SQL，计时并记录慢查询日志。
     *
     * @param sql     经过 SqlValidator 校验的 SELECT 语句
     * @param maxRows 最多返回行数（追加 LIMIT，防止全表扫描）
     * @param timeout DB 驱动层查询超时（通过 PreparedStatement.setQueryTimeout 设置）
     */
    public Mono<DataQueryResult> execute(String sql, int maxRows, Duration timeout) {
        return Mono.fromCallable(() -> {
            long startNs = System.nanoTime();
            String status = STATUS_SUCCESS;
            try {
                log.debug("Executing SQL: {}", sql);
                DataQueryResult result = connector.executeQuery(sql, maxRows, timeout);
                return result;
            } catch (Exception e) {
                status = STATUS_ERROR;
                throw e;
            } finally {
                long elapsedNs  = System.nanoTime() - startNs;
                long elapsedMs  = TimeUnit.NANOSECONDS.toMillis(elapsedNs);
                recordMetrics(sql, elapsedNs, status);
                if (elapsedMs >= slowQueryThresholdMs) {
                    // 慢查询 WARN 日志（SQL 前 120 字符，防止日志过大）
                    String sqlExcerpt = sql.length() > 120 ? sql.substring(0, 120) + "..." : sql;
                    log.warn("[SLOW QUERY] {}ms (threshold={}ms): {}", elapsedMs, slowQueryThresholdMs, sqlExcerpt);
                }
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── Micrometer ───────────────────────────────────────────────────────────

    private void recordMetrics(String sql, long elapsedNs, String status) {
        if (meterRegistry == null) return;
        try {
            Timer.builder(TIMER_NAME)
                .description("Data Agent SQL query execution time")
                .tag(TAG_STATUS, status)
                .register(meterRegistry)
                .record(elapsedNs, TimeUnit.NANOSECONDS);
        } catch (Exception e) {
            // 指标记录失败不应影响主流程
            log.debug("Failed to record query metrics: {}", e.getMessage());
        }
    }
}
