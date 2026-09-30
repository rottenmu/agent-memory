package com.zimo.module.agentmemory.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zimo.module.agentmemory.autoconfig.AgentMemoryProperties;
import com.zimo.module.ds.storage.StorageContext;
import com.zimo.module.ds.storage.StorageProvider;
import com.zimo.module.ds.storage.StorageRouter;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * 记忆存储引擎路由测试（通用存储后端 SPI 抽象验证）。
 *
 * <p>装配层已移除 MemoryStorageFactory，OLTP / OLAP 仓储由
 * {@link StorageRouter} 直接路由，本测试覆盖该路由行为。</p>
 */
class StorageRoutingTest {

    private static final class FakeOltpProvider implements StorageProvider<OltpMemoryRepository> {
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
            return new FakeOltpMemoryRepository() { };
        }
    }

    private static final class FakeOlapProvider implements StorageProvider<OlapAnalyticsRepository> {
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

    private static final StorageContext CONTEXT =
            new StorageContext("h2", "jdbc:h2:mem:test", "./data/test.arrow", (DataSource) null);

    private static OltpMemoryRepository routeOltp(
            List<StorageProvider<OltpMemoryRepository>> providers, String configuredEngine) {
        return StorageRouter.route(
                providers, configuredEngine, AgentMemoryProperties.DEFAULT_OLTP_ENGINE, CONTEXT, "OLTP");
    }

    private static OlapAnalyticsRepository routeOlap(
            List<StorageProvider<OlapAnalyticsRepository>> providers, String configuredEngine) {
        return StorageRouter.route(
                providers, configuredEngine, AgentMemoryProperties.DEFAULT_OLAP_ENGINE, CONTEXT, "OLAP");
    }

    @Test
    void routesToConfiguredOltpEngine() {
        List<StorageProvider<OltpMemoryRepository>> providers =
                List.of(new FakeOltpProvider("h2"), new FakeOltpProvider("mysql"));
        assertThat(routeOltp(providers, "mysql")).isNotNull();
        assertThat(routeOltp(providers, "h2")).isNotNull();
    }

    @Test
    void routesToConfiguredOlapEngine() {
        List<StorageProvider<OlapAnalyticsRepository>> providers =
                List.of(new FakeOlapProvider("arrow"), new FakeOlapProvider("duckdb"));
        assertThat(routeOlap(providers, "duckdb")).isNotNull();
        assertThat(routeOlap(providers, "arrow")).isNotNull();
    }

    @Test
    void fallsBackToDefaultWhenEngineUnknown() {
        // 未知引擎 → 回退默认 h2 / arrow
        assertThat(routeOltp(List.of(new FakeOltpProvider("h2")), "mysql")).isNotNull();
        assertThat(routeOlap(List.of(new FakeOlapProvider("arrow")), "clickhouse")).isNotNull();
    }

    @Test
    void usesDefaultWhenEngineNotConfigured() {
        assertThat(routeOltp(List.of(new FakeOltpProvider("h2")), null)).isNotNull();
        assertThat(routeOltp(List.of(new FakeOltpProvider("h2")), "  ")).isNotNull();
        assertThat(routeOlap(List.of(new FakeOlapProvider("arrow")), "")).isNotNull();
    }

    @Test
    void throwsWhenNoProviderAvailable() {
        assertThatThrownBy(() -> routeOltp(List.of(), "h2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OLTP");
        assertThatThrownBy(() -> routeOlap(List.of(), "arrow"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OLAP");
    }

    /** 回退时 Provider 必须收到实际生效的引擎名，而非配置中未注册的原始值（缺陷 D5 回归）。 */
    @Test
    void fallbackPassesResolvedEngineNameToProvider() {
        AtomicReference<String> seenEngine = new AtomicReference<>();
        StorageProvider<OltpMemoryRepository> capturing = new StorageProvider<>() {
            @Override
            public String engine() {
                return "h2";
            }

            @Override
            public OltpMemoryRepository create(StorageContext ctx) {
                seenEngine.set(ctx.engine());
                return new FakeOltpProvider("h2").create(ctx);
            }
        };

        routeOltp(List.of(capturing), "duckdb");

        assertThat(seenEngine.get()).isEqualTo("h2");
    }
}
