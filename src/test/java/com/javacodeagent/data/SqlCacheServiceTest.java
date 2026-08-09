package com.javacodeagent.data;

import com.javacodeagent.core.data.SqlCacheService;
import com.javacodeagent.core.data.model.Nl2SqlResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SqlCacheService 单元测试。
 * 覆盖：同一问题在不同数据源下互不串扰（回归第十五轮跨租户缓存泄漏修复）、命中/未命中。
 */
class SqlCacheServiceTest {

    private SqlCacheService cache;

    @BeforeEach
    void setUp() {
        cache = new SqlCacheService();
    }

    @Test
    void sameQuestion_differentDataSources_doNotShareCacheEntry() {
        Nl2SqlResult resultForDs1 = Nl2SqlResult.builder().sql("SELECT * FROM ds1_customers").build();
        Nl2SqlResult resultForDs2 = Nl2SqlResult.builder().sql("SELECT * FROM ds2_customers").build();

        cache.put("ds1", "show all customers", resultForDs1);
        cache.put("ds2", "show all customers", resultForDs2);

        assertThat(cache.get("ds1", "show all customers")).contains(resultForDs1);
        assertThat(cache.get("ds2", "show all customers")).contains(resultForDs2);
    }

    @Test
    void sameQuestion_sameDataSource_hitsCache() {
        Nl2SqlResult result = Nl2SqlResult.builder().sql("SELECT 1").build();
        cache.put("ds1", "how many rows", result);

        assertThat(cache.get("ds1", "how many rows")).contains(result);
        assertThat(cache.get("ds1", "How Many Rows?")).contains(result); // 归一化后应命中
    }

    @Test
    void nullDataSourceId_treatedAsDefault() {
        Nl2SqlResult result = Nl2SqlResult.builder().sql("SELECT 1").build();
        cache.put(null, "q", result);

        assertThat(cache.get("default", "q")).contains(result);
        assertThat(cache.get(null, "q")).contains(result);
    }

    @Test
    void unknownDataSource_missesCache() {
        cache.put("ds1", "q", Nl2SqlResult.builder().sql("SELECT 1").build());
        assertThat(cache.get("ds2", "q")).isEmpty();
    }
}
