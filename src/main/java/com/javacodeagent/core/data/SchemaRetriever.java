package com.javacodeagent.core.data;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaRetriever {

    private final DataSourceConnector connector;

    private static final int MAX_FULL_SCHEMA_TABLES = 10;
    private static final int TOP_K_TABLES = 5;

    /**
     * 检索与问题相关的 Schema（DDL + 样例行）。
     * 表数 ≤ 10 时返回全量 Schema；更多时用关键词评分取 top-K 表。
     */
    public Mono<String> retrieve(String question) {
        return Mono.fromCallable(() -> {
            List<String> allTables = connector.listTables();
            if (allTables.isEmpty()) return "-- No tables found in database: " + connector.getDatabaseName();

            List<String> selected = allTables.size() <= MAX_FULL_SCHEMA_TABLES
                ? allTables
                : selectByKeyword(question, allTables);

            log.debug("Schema retrieval: {} tables total, {} selected for question: {}",
                allTables.size(), selected.size(), question);
            return connector.getTableInfo(selected);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private List<String> selectByKeyword(String question, List<String> tables) {
        String lower = question.toLowerCase();
        Set<String> keywords = new HashSet<>(Arrays.asList(lower.split("[\\s,，。？！、\\-_]+")));
        keywords.removeIf(k -> k.length() < 2);

        return tables.stream()
            .sorted(Comparator.comparingInt((String t) -> -scoreTable(t.toLowerCase(), keywords)))
            .limit(TOP_K_TABLES)
            .collect(Collectors.toList());
    }

    private int scoreTable(String tableName, Set<String> keywords) {
        return (int) keywords.stream().filter(tableName::contains).count();
    }
}
