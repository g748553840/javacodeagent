package com.javacodeagent.core.data;

import com.javacodeagent.core.data.DataAgentConstants;
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

    public Mono<DataQueryResult> execute(String sql) {
        return execute(sql, DataAgentConstants.DEFAULT_MAX_ROWS, DataAgentConstants.DEFAULT_QUERY_TIMEOUT);
    }

    public Mono<DataQueryResult> execute(String sql, int maxRows, Duration timeout) {
        return Mono.fromCallable(() -> {
            log.debug("Executing SQL: {}", sql);
            return connector.executeQuery(sql, maxRows, timeout);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
