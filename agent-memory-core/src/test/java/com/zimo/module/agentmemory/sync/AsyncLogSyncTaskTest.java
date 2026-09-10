package com.zimo.module.agentmemory.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.zimo.module.agentmemory.analytics.impl.ArrowOlapAnalyticsRepository;
import com.zimo.module.agentmemory.model.L0RawLog;
import com.zimo.module.agentmemory.storage.OltpMemoryRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AsyncLogSyncTask 游标增量同步测试。
 */
class AsyncLogSyncTaskTest {

    @TempDir
    Path tempDir;

    /** 内存版 OLTP 仓储：模拟 H2 L0 日志追加。 */
    private static final class FakeOltp implements OltpMemoryRepository {
        final List<L0RawLog> logs = new ArrayList<>();

        void add(long id, String sessionId) {
            logs.add(new L0RawLog(id, "trace-" + id, sessionId, "u1", id * 1000L,
                    "user", "内容" + id, 10, null, null));
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
        @Override public void saveAtomicMemory(com.zimo.module.agentmemory.model.L1AtomicMemory memory) { }
        @Override public void deleteAtomicMemory(String id) { }
        @Override public List<com.zimo.module.agentmemory.model.L1AtomicMemory> recallAtomicByType(
                String userId, String memoryType, int limit) { return List.of(); }
        @Override public List<com.zimo.module.agentmemory.model.L1AtomicMemory> recallAtomicBySession(
                String sessionId, int limit) { return List.of(); }
        @Override public List<com.zimo.module.agentmemory.model.L1AtomicMemory> listAtomicByTrace(String traceId) { return List.of(); }
        @Override public void saveSceneBlock(com.zimo.module.agentmemory.model.L2SceneBlock block) { }
        @Override public com.zimo.module.agentmemory.model.L2SceneBlock recallSceneBySession(String sessionId) { return null; }
        @Override public List<com.zimo.module.agentmemory.model.L2SceneBlock> listScenesBySession(String sessionId, int limit) { return List.of(); }
        @Override public void savePersona(com.zimo.module.agentmemory.model.L3Persona persona) { }
        @Override public com.zimo.module.agentmemory.model.L3Persona getPersona(String userId, String personaType) { return null; }
        @Override public void deletePersona(String userId, String personaType) { }
        @Override public List<com.zimo.module.agentmemory.model.L3Persona> listPersonas(String userId) { return List.of(); }
        @Override public List<L0RawLog> drillDownToRawLog(String traceId) { return List.of(); }
        @Override public long countL0Total() { return 0; }
        @Override public long countL0Today() { return 0; }
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
}
