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

    public static final String SYSTEM_USER_ID = "system";

    public static final String DEFAULT_DISPLAY_TYPE = "response_table";

    public static final String DASHBOARD_TITLE_SUFFIX = " 综合看板";

    public static final int TABLE_NAME_UUID_LENGTH = 12;
    public static final int MAX_COLUMN_NAME_LENGTH = 60;

    private DataAgentConstants() {}
}
