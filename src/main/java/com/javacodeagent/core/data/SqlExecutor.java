package com.javacodeagent.core.data;

import com.javacodeagent.core.data.model.DataQueryResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class SqlExecutor {

    private final DataSourceConnector connector;

    private static final int DEFAULT_MAX_ROWS = 200;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    public Mono<DataQueryResult> execute(String sql) {
        return execute(sql, DEFAULT_MAX_ROWS, DEFAULT_TIMEOUT);
    }

    public Mono<DataQueryResult> execute(String sql, int maxRows, Duration timeout) {
        return Mono.fromCallable(() -> {
            log.debug("Executing SQL: {}", sql);
            return connector.executeQuery(sql, maxRows, timeout);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
