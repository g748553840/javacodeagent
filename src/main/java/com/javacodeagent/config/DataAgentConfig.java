package com.javacodeagent.config;

import com.javacodeagent.core.data.DataSourceConnector;
import com.javacodeagent.core.data.JdbcDataSourceConnector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 数据分析 Agent 的数据源配置。
 *
 * <p>默认使用应用内置 H2 数据库（dialect=h2, dbName=PUBLIC）。
 *
 * <p>若需将 NL2SQL 分析查询指向外部数据库（MySQL / PostgreSQL 等），
 * 可在 application.yml 中配置 {@code data-agent.datasource.*}，
 * 此时 Data Agent 使用独立连接，不影响 Spring JPA 实体存储（H2）。
 */
@Slf4j
@Configuration
public class DataAgentConfig {

    @Value("${data-agent.dialect:h2}")
    private String dialect;

    @Value("${data-agent.db-name:PUBLIC}")
    private String dbName;

    @Value("${data-agent.datasource.url:}")
    private String dataAgentDsUrl;

    @Value("${data-agent.datasource.username:}")
    private String dataAgentDsUser;

    @Value("${data-agent.datasource.password:}")
    private String dataAgentDsPass;

    @Value("${data-agent.datasource.driver-class-name:}")
    private String dataAgentDsDriver;

    /**
     * Creates the DataSourceConnector for NL2SQL analysis.
     * Uses the dedicated data-agent datasource if configured, otherwise falls back to
     * the application's embedded JdbcTemplate (H2).
     */
    @Bean
    public DataSourceConnector dataSourceConnector(JdbcTemplate defaultJdbcTemplate) {
        if (!dataAgentDsUrl.isBlank()) {
            log.info("Data Agent using external datasource: dialect={} url={}", dialect, dataAgentDsUrl);
            DataSourceBuilder<?> builder = DataSourceBuilder.create()
                .url(dataAgentDsUrl)
                .username(dataAgentDsUser)
                .password(dataAgentDsPass);
            if (!dataAgentDsDriver.isBlank()) {
                builder = builder.driverClassName(dataAgentDsDriver);
            }
            return new JdbcDataSourceConnector(new JdbcTemplate(builder.build()), dialect, dbName);
        }
        log.info("Data Agent using embedded H2 datasource: dialect={} dbName={}", dialect, dbName);
        return new JdbcDataSourceConnector(defaultJdbcTemplate, dialect, dbName);
    }
}
