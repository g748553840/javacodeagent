package com.javacodeagent.config;

import com.javacodeagent.core.data.DataSourceConnector;
import com.javacodeagent.core.data.DataSourceManager;
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
 *
 * <p>创建的默认连接器会自动注册到 {@link DataSourceManager}，
 * 动态添加的额外数据源通过 {@code POST /api/v1/data-agent/datasources} 注册。
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
     * 多数据源管理器 Bean（先于 dataSourceConnector 初始化）。
     */
    @Bean
    public DataSourceManager dataSourceManager() {
        return new DataSourceManager();
    }

    /**
     * 创建并注册默认 DataSourceConnector。
     *
     * <p>若配置了 {@code data-agent.datasource.url}，则使用外部数据库；
     * 否则使用 Spring 内置 H2（{@code JdbcTemplate} Bean）。
     */
    @Bean
    public DataSourceConnector dataSourceConnector(JdbcTemplate defaultJdbcTemplate,
                                                   DataSourceManager dataSourceManager) {
        DataSourceConnector connector;
        if (!dataAgentDsUrl.isBlank()) {
            log.info("Data Agent using external datasource: dialect={} url={}", dialect, dataAgentDsUrl);
            DataSourceBuilder<?> builder = DataSourceBuilder.create()
                .url(dataAgentDsUrl)
                .username(dataAgentDsUser)
                .password(dataAgentDsPass);
            if (!dataAgentDsDriver.isBlank()) {
                builder = builder.driverClassName(dataAgentDsDriver);
            }
            // 此连接池由本方法自行创建（非 Spring 管理的 Bean），必须作为 ownedDataSource
            // 传入，否则应用关闭时 DataSourceManager.closeAll() 调用 connector.close()
            // 不会真正关闭这个 HikariCP 连接池，导致进程退出前连接/线程一直挂着
            javax.sql.DataSource externalDs = builder.build();
            connector = new JdbcDataSourceConnector(new JdbcTemplate(externalDs), dialect, dbName, externalDs);
        } else {
            log.info("Data Agent using embedded H2 datasource: dialect={} dbName={}", dialect, dbName);
            connector = new JdbcDataSourceConnector(defaultJdbcTemplate, dialect, dbName);
        }
        // 注册为默认数据源
        dataSourceManager.registerDefault(connector);
        return connector;
    }
}
