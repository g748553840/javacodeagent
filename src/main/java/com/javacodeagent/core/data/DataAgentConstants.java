package com.javacodeagent.core.data;

import java.time.Duration;

public final class DataAgentConstants {

    public static final int DEFAULT_MAX_ROWS = 200;
    public static final Duration DEFAULT_QUERY_TIMEOUT = Duration.ofSeconds(30);

    public static final int DEFAULT_SAMPLE_ROWS = 3;
    public static final int DEFAULT_INSIGHT_SAMPLE_ROWS = 10;

    public static final String CONV_PREFIX_NL2SQL = "nl2sql-";
    public static final String CONV_PREFIX_INSIGHT = "insight-";
    public static final String CONV_PREFIX_DASHBOARD = "dashboard-";
    public static final String CONV_PREFIX_ANOMALY = "anomaly-";
    public static final String CONV_PREFIX_VOLATILITY = "volatility-";
    public static final String CONV_PREFIX_REPORT = "report-";

    public static final String SYSTEM_USER_ID = "system";

    public static final String DEFAULT_DISPLAY_TYPE = "response_table";

    public static final String DASHBOARD_TITLE_SUFFIX = " 综合看板";

    public static final int TABLE_NAME_UUID_LENGTH = 12;
    public static final int MAX_COLUMN_NAME_LENGTH = 60;

    /** DDL 中用于存储导入列值的 VARCHAR 最大长度。 */
    public static final int IMPORT_VARCHAR_LENGTH = 1000;

    /** 检测 LIMIT 子句时 "LIMIT " 字符串的长度。 */
    public static final int LIMIT_KEYWORD_LENGTH = 6; // "LIMIT ".length()

    /** 图表并发 SQL 执行数上限（与 LLM prompt 约定的 2-4 张一致）。 */
    public static final int DASHBOARD_CHART_CONCURRENCY = 4;

    /** 数据分析时 prompt 中的最大样本行数。 */
    public static final int DATA_SAMPLE_MAX_ROWS = 50;

    /** SQL 校验错误提示中显示的最大 SQL 前缀长度。 */
    public static final int SQL_ERROR_EXCERPT_LENGTH = 30;

    /** Excel/CSV 上传文件大小上限（字节），超出直接拒绝，防止内存耗尽型 DoS。 */
    public static final long EXCEL_UPLOAD_MAX_BYTES = 20L * 1024 * 1024;

    private DataAgentConstants() {}
}
