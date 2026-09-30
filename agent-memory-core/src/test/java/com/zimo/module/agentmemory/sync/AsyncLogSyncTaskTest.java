package com.zimo.module.agentmemory.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.zimo.module.agentmemory.analytics.impl.ArrowOlapAnalyticsRepository;
import com.zimo.module.agentmemory.model.L0RawLog;
import com.zimo.module.agentmemory.storage.FakeOltpMemoryRepository;
import com.zimo.module.agentmemory.storage.impl.H2OltpMemoryRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * AsyncLogSyncTask 游标增量同步测试。
 */
class AsyncLogSyncTaskTest {

    @TempDir
    Path tempDir;

    /** 内存版 OLTP 仓储：模拟 H2 L0 日志追加；只覆写本测试用到的 L0 读取。 */
    private static final class FakeOltp extends FakeOltpMemoryRepository {
        final List<L0RawLog> logs = new ArrayList<>();

        void add(long id, String sessionId) {
            add(id, sessionId, null);
        }

        void add(long id, String sessionId, String source) {
            logs.add(new L0RawLog(id, "trace-" + id, sessionId, "u1", id * 1000L,
                    "user", "内容" + id, 10, null, source));
        }

        @Override public void saveRawLog(L0RawLog log) { logs.add(log); }

        @Override public List<L0RawLog> listRawLogsByTrace(String traceId) {
            return logs.stream().filter(l -> l.traceId().equals(traceId)).toList();
        }

        @Override public List<L0RawLog> listRawLogsBySession(String sessionId) {
            return logs.stream().filter(l -> l.sessionId().equals(sessionId)).toList();
        }

        @Override public List<L0RawLog> listRawLogsPage(int offset, int limit) {
            return logs.stream().skip(offset).limit(limit).toList();
        }

        @Override public List<L0RawLog> listRawLogsSince(long afterId, int limit) {
            return logs.stream().filter(l -> l.id() > afterId).limit(limit).toList();
        }
    }

    @Test
    void syncsIncrementallyByCursor() {
        FakeOltp oltp = new FakeOltp();
        ArrowOlapAnalyticsRepository olap = new ArrowOlapAnalyticsRepository(
                tempDir.resolve("l0_log.arrow"));
        AsyncLogSyncTask task = new AsyncLogSyncTask(oltp, olap);

        // 第一轮：2 条
        oltp.add(1, "s1");
        oltp.add(2, "s1");
        task.run();
        assertThat(olap.syncCursor()).isEqualTo(2);
        assertThat(olap.sessionStats()).hasSize(1);

        // 第二轮：新增 1 条（id=3），应只追加
        oltp.add(3, "s2");
        task.run();
        assertThat(olap.syncCursor()).isEqualTo(3);
        assertThat(olap.sessionStats()).hasSize(2);

        // 无新增：游标不变
        task.run();
        assertThat(olap.syncCursor()).isEqualTo(3);

        // 新实例（模拟重启）：从游标文件恢复，不重复同步
        ArrowOlapAnalyticsRepository restarted = new ArrowOlapAnalyticsRepository(
                tempDir.resolve("l0_log.arrow"));
        assertThat(restarted.syncCursor()).isEqualTo(3);
        assertThat(restarted.sessionStats()).hasSize(2);
    }

    @Test
    void preservesSourceAcrossFullRebuildIncrementalSyncAndRestart() {
        FakeOltp oltp = new FakeOltp();
        ArrowOlapAnalyticsRepository olap = new ArrowOlapAnalyticsRepository(
                tempDir.resolve("source-log.arrow"));
        AsyncLogSyncTask task = new AsyncLogSyncTask(oltp, olap);

        oltp.add(1, "s1", "user_message");
        oltp.add(2, "s1", "tool_result");
        task.run();

        assertSources(olap, List.of("user_message", "tool_result"));

        oltp.add(3, "s1", "context_injection");
        task.run();
        assertSources(olap, List.of("user_message", "tool_result", "context_injection"));

        ArrowOlapAnalyticsRepository restarted = new ArrowOlapAnalyticsRepository(
                tempDir.resolve("source-log.arrow"));
        assertSources(restarted, List.of("user_message", "tool_result", "context_injection"));
    }

    @Test
    void preservesSourceFromH2AcrossFullRebuildIncrementalSyncAndRestart() {
        String databaseName = "source_" + UUID.randomUUID().toString().replace("-", "");
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1", "sa", "");
        H2OltpMemoryRepository oltp = new H2OltpMemoryRepository(dataSource);
        ArrowOlapAnalyticsRepository olap = new ArrowOlapAnalyticsRepository(
                tempDir.resolve("h2-source-log.arrow"));
        AsyncLogSyncTask task = new AsyncLogSyncTask(oltp, olap);

        oltp.saveRawLog(L0RawLog.forInsert(
                "trace-1", "s1", "u1", 1000L, "user", "问题", 2, null, "user_message"));
        oltp.saveRawLog(L0RawLog.forInsert(
                "trace-2", "s1", "u1", 2000L, "tool", "结果", 3, null, "tool_result"));
        task.run();
        assertSources(olap, List.of("user_message", "tool_result"));

        oltp.saveRawLog(L0RawLog.forInsert(
                "trace-3", "s1", "u1", 3000L, "system", "注入片段", 1, null, "context_injection"));
        task.run();
        assertSources(olap, List.of("user_message", "tool_result", "context_injection"));

        ArrowOlapAnalyticsRepository restarted = new ArrowOlapAnalyticsRepository(
                tempDir.resolve("h2-source-log.arrow"));
        assertSources(restarted, List.of("user_message", "tool_result", "context_injection"));
    }

    private static void assertSources(ArrowOlapAnalyticsRepository olap, List<String> expectedSources) {
        List<java.util.Map<String, Object>> rows = olap.query(
                "SELECT source FROM olap_l0_log ORDER BY trace_id");
        assertThat(rows).hasSize(expectedSources.size());
        assertThat(rows).extracting(row -> row.get("source"))
                .containsExactlyElementsOf(expectedSources);
    }
}
