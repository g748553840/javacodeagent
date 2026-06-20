package com.javacodeagent.config;

import com.javacodeagent.core.data.DataSourceConnector;
import com.javacodeagent.core.data.JdbcDataSourceConnector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 数据分析 Agent 的数据源配置。
 * 默认使用应用内置 H2 数据库（dialect=h2, dbName=PUBLIC）。
 * 可通过 application.yml 的 data-agent.* 属性切换到外部 MySQL / PostgreSQL 等数据库。
 */
@Configuration
public class DataAgentConfig {

    @Value("${data-agent.dialect:h2}")
    private String dialect;

    @Value("${data-agent.db-name:PUBLIC}")
    private String dbName;

    /**
     * 使用应用内置 JdbcTemplate（H2）作为数据分析数据源。
     * 生产环境中可替换为指向独立分析数据库的 JdbcTemplate Bean。
     */
    @Bean
    public DataSourceConnector dataSourceConnector(JdbcTemplate jdbcTemplate) {
        return new JdbcDataSourceConnector(jdbcTemplate, dialect, dbName);
    }
}
