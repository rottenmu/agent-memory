package com.zimo.module.agentmemory.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zimo.module.agentmemory.storage.spi.OlapStorageProvider;
import com.zimo.module.agentmemory.storage.spi.OltpStorageProvider;
import com.zimo.module.agentmemory.storage.spi.StorageContext;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * MemoryStorageFactory 引擎路由测试（存储后端 SPI 抽象验证）。
 */
class MemoryStorageFactoryTest {

    private static final class FakeOltpProvider implements OltpStorageProvider {
        private final String engine;

        FakeOltpProvider(String engine) {
            this.engine = engine;
        }

        @Override
        public String engine() {
            return engine;
        }

        @Override
        public OltpMemoryRepository create(StorageContext context) {
            return new OltpMemoryRepository() {
                @Override public void saveRawLog(com.zimo.module.agentmemory.model.L0RawLog log) { }
                @Override public java.util.List<com.zimo.module.agentmemory.model.L0RawLog> listRawLogsByTrace(String traceId) { return List.of(); }
                @Override public java.util.List<com.zimo.module.agentmemory.model.L0RawLog> listRawLogsBySession(String sessionId) { return List.of(); }
                @Override public java.util.List<com.zimo.module.agentmemory.model.L0RawLog> listRawLogsPage(int offset, int limit) { return List.of(); }
                @Override public java.util.List<com.zimo.module.agentmemory.model.L0RawLog> listRawLogsSince(long afterId, int limit) { return List.of(); }
                @Override public void saveAtomicMemory(com.zimo.module.agentmemory.model.L1AtomicMemory memory) { }
                @Override public void deleteAtomicMemory(String id) { }
                @Override public java.util.List<com.zimo.module.agentmemory.model.L1AtomicMemory> recallAtomicByType(String userId, String memoryType, int limit) { return List.of(); }
                @Override public java.util.List<com.zimo.module.agentmemory.model.L1AtomicMemory> recallAtomicBySession(String sessionId, int limit) { return List.of(); }
                @Override public java.util.List<com.zimo.module.agentmemory.model.L1AtomicMemory> listAtomicByTrace(String traceId) { return List.of(); }
                @Override public void saveSceneBlock(com.zimo.module.agentmemory.model.L2SceneBlock block) { }
                @Override public com.zimo.module.agentmemory.model.L2SceneBlock recallSceneBySession(String sessionId) { return null; }
                @Override public java.util.List<com.zimo.module.agentmemory.model.L2SceneBlock> listScenesBySession(String sessionId, int limit) { return List.of(); }
                @Override public void savePersona(com.zimo.module.agentmemory.model.L3Persona persona) { }
                @Override public com.zimo.module.agentmemory.model.L3Persona getPersona(String userId, String personaType) { return null; }
                @Override public void deletePersona(String userId, String personaType) { }
                @Override public java.util.List<com.zimo.module.agentmemory.model.L3Persona> listPersonas(String userId) { return List.of(); }
                @Override public java.util.List<com.zimo.module.agentmemory.model.L0RawLog> drillDownToRawLog(String traceId) { return List.of(); }
        @Override public long countL0Total() { return 0; }
        @Override public long countL0Today() { return 0; }
            };
        }
    }

    private static final class FakeOlapProvider implements OlapStorageProvider {
        private final String engine;

        FakeOlapProvider(String engine) {
            this.engine = engine;
        }

        @Override
        public String engine() {
            return engine;
        }

        @Override
        public OlapAnalyticsRepository create(StorageContext context) {
            return new OlapAnalyticsRepository() {
                @Override public java.util.List<java.util.Map<String, Object>> sessionStats() { return List.of(); }
                @Override public java.util.List<java.util.Map<String, Object>> userDailyActivity(String userId, int days) { return List.of(); }
                @Override public java.util.List<java.util.Map<String, Object>> memoryDistillationStats() { return List.of(); }
                @Override public java.util.List<java.util.Map<String, Object>> traceEvents(String traceId) { return List.of(); }
                @Override public java.util.List<java.util.Map<String, Object>> query(String sql) { return List.of(); }
                @Override public void refresh() { }
                @Override public void replaceRows(java.util.List<Object[]> rows) { }
                @Override public void appendRows(java.util.List<Object[]> newRows) { }
                @Override public long syncCursor() { return 0L; }
                @Override public void updateSyncCursor(long lastSyncedId) { }
            };
        }
    }

    private final StorageContext context =
            new StorageContext("h2", "jdbc:h2:mem:test", "./data/test.arrow", (DataSource) null);

    @Test
    void routesToConfiguredOltpEngine() {
        MemoryStorageFactory factory = new MemoryStorageFactory(
                List.of(new FakeOltpProvider("h2"), new FakeOltpProvider("mysql")),
                List.of(new FakeOlapProvider("arrow")),
                context);
        assertThat(factory.createOltp("mysql")).isNotNull();
        assertThat(factory.createOltp("h2")).isNotNull();
    }

    @Test
    void fallsBackToDefaultWhenEngineUnknown() {
        MemoryStorageFactory factory = new MemoryStorageFactory(
                List.of(new FakeOltpProvider("h2")),
                List.of(new FakeOlapProvider("arrow")),
                context);
        // 未知引擎 → 回退默认 h2 / arrow
        assertThat(factory.createOltp("duckdb")).isNotNull();
        assertThat(factory.createOlap("duckdb")).isNotNull();
    }

    @Test
    void throwsWhenNoProviderAvailable() {
        MemoryStorageFactory factory = new MemoryStorageFactory(
                List.of(), List.of(), context);
        assertThatThrownBy(() -> factory.createOltp("h2"))
                .isInstanceOf(IllegalStateException.class);
    }
}
