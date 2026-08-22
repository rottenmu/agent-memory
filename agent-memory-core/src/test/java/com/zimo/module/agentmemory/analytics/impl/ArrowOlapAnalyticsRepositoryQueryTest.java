package com.zimo.module.agentmemory.analytics.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ArrowOlapAnalyticsRepository Calcite SQL 查询能力测试（复现聚合/过滤问题）。
 */
class ArrowOlapAnalyticsRepositoryQueryTest {

    @TempDir
    Path tempDir;

    private ArrowOlapAnalyticsRepository newRepo() {
        return new ArrowOlapAnalyticsRepository(tempDir.resolve("l0_log.arrow"));
    }

    private void seed(ArrowOlapAnalyticsRepository repo) {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"t1", "s1", "u1", 1000L, "user", "你好", 10L});
        rows.add(new Object[]{"t2", "s1", "u1", 2000L, "assistant", "你好，有什么可以帮你", 20L});
        rows.add(new Object[]{"t3", "s2", "u2", 3000L, "user", "帮我查库存", 15L});
        repo.replaceRows(rows);
    }

    @Test
    void selectStarReturnsAllRows() {
        ArrowOlapAnalyticsRepository repo = newRepo();
        seed(repo);
        List<Map<String, Object>> result = repo.query("SELECT * FROM olap_l0_log");
        assertThat(result).hasSize(3);
    }

    @Test
    void whereFilterReturnsMatchingRows() {
        ArrowOlapAnalyticsRepository repo = newRepo();
        seed(repo);
        List<Map<String, Object>> result = repo.query(
                "SELECT trace_id FROM olap_l0_log WHERE role = 'user'");
        assertThat(result).hasSize(2);
    }

    @Test
    void groupByReturnsAggregatedRows() {
        ArrowOlapAnalyticsRepository repo = newRepo();
        seed(repo);
        List<Map<String, Object>> result = repo.query(
                "SELECT role, COUNT(*) AS cnt FROM olap_l0_log GROUP BY role");
        assertThat(result).hasSize(2);
    }

    @Test
    void distinctReturnsUniqueValues() {
        ArrowOlapAnalyticsRepository repo = newRepo();
        seed(repo);
        List<Map<String, Object>> result = repo.query("SELECT DISTINCT session_id FROM olap_l0_log");
        assertThat(result).hasSize(2);
    }
}
