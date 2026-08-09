package com.javacodeagent.core.data;

import com.javacodeagent.core.data.JdbcDataSourceConnector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多数据源管理器。
 *
 * <p>功能：
 * <ul>
 *   <li>动态注册 / 注销 JDBC 数据源</li>
 *   <li>按 {@code dataSourceId} 路由 {@link DataSourceConnector} 查询</li>
 *   <li>列出已注册的数据源及状态</li>
 *   <li>连接池复用：{@link DataSourceBuilder} 默认使用 HikariCP</li>
 * </ul>
 *
 * <p>在 {@link com.javacodeagent.config.DataAgentConfig} 中注册默认数据源（H2 或配置的外部源）。
 * {@link DataAgentPipeline} 通过 {@link #getConnector(String)} 获取目标连接器后传入
 * {@link SchemaRetriever#retrieve(String, DataSourceConnector, String)}。
 */
@Slf4j
@Service
public class DataSourceManager {

    /** 注册表：dataSourceId → DataSourceConnector */
    private final ConcurrentHashMap<String, DataSourceConnector> connectors = new ConcurrentHashMap<>();

    /**
     * 数据源注册信息（用于 list 端点返回元数据）。
     */
    private final ConcurrentHashMap<String, DataSourceInfo> infos = new ConcurrentHashMap<>();

    private static final String DEFAULT_ID = "default";

    // ── 注册 / 注销 ───────────────────────────────────────────────────────────

    /**
     * 注册默认数据源（启动时由 DataAgentConfig 调用）。
     */
    public void registerDefault(DataSourceConnector connector) {
        register(DEFAULT_ID, connector, connector.getDialect(), connector.getDatabaseName(),
            "Default datasource (from application.yml data-agent config)");
    }

    /**
     * 动态注册数据源（REST API 或测试使用）。
     *
     * @param id          数据源唯一标识（调用方自定义）
     * @param connector   已初始化的连接器
     * @param dialect     数据库方言（mysql/postgresql/h2 等，仅用于展示）
     * @param dbName      数据库名称（仅用于展示）
     * @param description 描述（仅用于展示）
     */
    public void register(String id, DataSourceConnector connector,
                         String dialect, String dbName, String description) {
        connectors.put(id, connector);
        infos.put(id, new DataSourceInfo(id, dialect, dbName, description, true));
        log.info("Registered datasource '{}': dialect={}, db={}", id, dialect, dbName);
    }

    /**
     * 动态注册数据源（通过 JDBC 连接参数，内部创建连接池）。
     *
     * @param id           数据源唯一标识
     * @param url          JDBC URL（必填）
     * @param username     用户名
     * @param password     密码
     * @param driverClass  JDBC 驱动类名（可为空，HikariCP 会自动推断）
     * @param dialect      方言（mysql / postgresql / clickhouse / h2 等）
     * @param dbName       逻辑数据库名
     * @param description  描述
     */
    public void registerJdbc(String id, String url, String username, String password,
                             String driverClass, String dialect, String dbName, String description) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("JDBC url must not be null or blank");
        }
        DataSourceBuilder<?> builder = DataSourceBuilder.create()
            .url(url)
            .username(username)
            .password(password);
        if (driverClass != null && !driverClass.isBlank()) {
            builder = builder.driverClassName(driverClass);
        }
        DataSource ds = builder.build();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        // 传入 ds 作为 ownedDataSource：此连接池由本方法动态创建，不受 Spring 容器管理，
        // unregister() 时必须通过 connector.close() 显式关闭，否则 HikariCP 连接池
        // （默认 10 连接 + 若干维护线程）会在每次 register/unregister 循环中永久泄漏
        DataSourceConnector connector = new JdbcDataSourceConnector(jdbc, dialect, dbName, ds);
        register(id, connector, dialect, dbName, description);
    }

    /**
     * 注销并关闭数据源。
     */
    public void unregister(String id) {
        if (DEFAULT_ID.equals(id)) {
            throw new IllegalArgumentException("Cannot unregister the default datasource");
        }
        DataSourceConnector removed = connectors.remove(id);
        infos.remove(id);
        if (removed != null) {
            removed.close();
            log.info("Unregistered datasource: {}", id);
        }
    }

    // ── 查询 ─────────────────────────────────────────────────────────────────

    /**
     * 获取数据源连接器。
     *
     * @param dataSourceId 数据源 ID（null 或空时返回默认数据源）
     * @return 对应连接器
     * @throws IllegalArgumentException 不存在时抛出（让 Pipeline 返回可读错误）
     */
    public DataSourceConnector getConnector(String dataSourceId) {
        String id = (dataSourceId == null || dataSourceId.isBlank()) ? DEFAULT_ID : dataSourceId;
        DataSourceConnector connector = connectors.get(id);
        if (connector == null) {
            throw new IllegalArgumentException(
                "DataSource not found: '" + id + "'. Available: " + connectors.keySet());
        }
        return connector;
    }

    /**
     * 获取默认数据源连接器。
     */
    public DataSourceConnector getDefaultConnector() {
        return getConnector(DEFAULT_ID);
    }

    /**
     * 列出所有已注册数据源的元信息。
     */
    public List<DataSourceInfo> listDataSources() {
        return List.copyOf(infos.values());
    }

    /**
     * 检查数据源 ID 是否已注册。
     */
    public boolean contains(String dataSourceId) {
        return connectors.containsKey(dataSourceId);
    }

    /**
     * 获取所有已注册的数据源 ID 集合。
     */
    public Set<String> getIds() {
        return Set.copyOf(connectors.keySet());
    }

    @PreDestroy
    public void closeAll() {
        connectors.values().forEach(DataSourceConnector::close);
        log.info("All data source connectors closed.");
    }

    // ── 数据模型 ──────────────────────────────────────────────────────────────

    /**
     * 数据源元信息（用于 list API 响应）。
     */
    public record DataSourceInfo(
        String id,
        String dialect,
        String dbName,
        String description,
        boolean active
    ) {}
}
