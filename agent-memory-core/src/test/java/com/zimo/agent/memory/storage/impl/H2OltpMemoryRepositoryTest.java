package com.zimo.agent.memory.storage.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zimo.agent.memory.model.L1AtomicMemory;
import com.zimo.agent.memory.model.L3Persona;
import com.zimo.agent.memory.model.UserMemoryRow;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * H2 OLTP 仓储的表结构迁移、隔离与分页行为测试。
 *
 * <p>其中「旧库原地迁移」是本模块风险最高的一步：生产库是 MVStore 文件库，
 * 加列必须不重建表、不丢数据。该用例用「先建旧结构、插旧数据，再用新版本打开」
 * 的方式复现真实升级路径。</p>
 */
class H2OltpMemoryRepositoryTest {

    private static DataSource dataSource(String db) {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    private static H2OltpMemoryRepository repository(String db) {
        return new H2OltpMemoryRepository(dataSource(db));
    }

    @Test
    @DisplayName("旧库缺列时原地补列并回填，旧数据仍可读（不重建表）")
    void migratesLegacyDatabaseInPlace() {
        DataSource ds = dataSource("legacy_migration");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        // 1. 造「补列之前」的表结构与数据
        jdbc.execute("CREATE TABLE l3_persona ("
                + " id VARCHAR(32) PRIMARY KEY, user_id VARCHAR(64) NOT NULL,"
                + " persona_type VARCHAR(32) NOT NULL, content CLOB NOT NULL,"
                + " version INT DEFAULT 1, updated_ts BIGINT NOT NULL)");
        jdbc.update("INSERT INTO l3_persona"
                        + " (id, user_id, persona_type, content, version, updated_ts)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                "old-p1", "u1", "persona", "旧画像", 1, 1_700_000_000_000L);
        jdbc.execute("CREATE TABLE l1_atomic_memory ("
                + " id VARCHAR(32) PRIMARY KEY, trace_id VARCHAR(64) NOT NULL,"
                + " session_id VARCHAR(64) NOT NULL, user_id VARCHAR(64),"
                + " memory_type VARCHAR(32) NOT NULL, content CLOB NOT NULL,"
                + " embedding BLOB, ts BIGINT NOT NULL)");
        jdbc.update("INSERT INTO l1_atomic_memory"
                        + " (id, trace_id, session_id, user_id, memory_type, content, ts)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                "old-a1", "trace-1", "user-u1", "u1", "custom", "旧原子记忆", 1_700_000_001_000L);

        // 2. 用新版本打开同一库 → 触发 ADD COLUMN IF NOT EXISTS + 回填
        H2OltpMemoryRepository repo = new H2OltpMemoryRepository(ds);

        // 3. 旧数据可读，且新列被回填为可解释的默认值
        L3Persona persona = repo.getPersona("default", "u1", "persona");
        assertThat(persona).isNotNull();
        assertThat(persona.content()).isEqualTo("旧画像");
        assertThat(persona.tenantId()).isEqualTo("default");
        assertThat(persona.importance()).isEqualTo(0.5);
        assertThat(persona.accessCount()).isZero();
        assertThat(persona.createdAt()).isEqualTo(1_700_000_000_000L);

        List<UserMemoryRow> rows = repo.pageUserMemory("default", "u1", null, 50, 0);
        assertThat(rows).hasSize(2);
        assertThat(repo.countUserMemory("default", "u1", null)).isEqualTo(2);
    }

    @Test
    @DisplayName("recallAtomicByType 拒绝空类型（D9 的根因形态不可再表达）")
    void rejectsBlankMemoryType() {
        H2OltpMemoryRepository repo = repository("blank_type");

        assertThatThrownBy(() -> repo.recallAtomicByType("t1", "u1", "", 10, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memoryType");
        assertThatThrownBy(() -> repo.recallAtomicByType("t1", "u1", null, 10, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("按 id 删除画像会失效进程缓存，不出现「库已删、缓存仍在」")
    void invalidatesPersonaCacheOnDeleteById() {
        H2OltpMemoryRepository repo = repository("cache_invalidate");
        repo.savePersona(L3Persona.create("p1", "u1", "persona", "画像内容", 1, 1000L, "t1"));
        // 先读一次把画像放进缓存，删除若不失效缓存就会读到脏值
        assertThat(repo.getPersona("t1", "u1", "persona")).isNotNull();

        assertThat(repo.deletePersonaById("t1", "p1")).isEqualTo(1);
        assertThat(repo.getPersona("t1", "u1", "persona")).isNull();
    }

    @Test
    @DisplayName("删除返回真实影响行数，且不越过租户边界")
    void deleteRespectsTenantBoundary() {
        H2OltpMemoryRepository repo = repository("delete_tenant");
        repo.saveAtomicMemory(L1AtomicMemory.create(
                "a1", "trace-1", "s1", "u1", "custom", "内容", 1000L, "t1"));

        assertThat(repo.deleteAtomicMemory("t2", "a1")).isZero();
        assertThat(repo.deleteAtomicMemory("t1", "a1")).isEqualTo(1);
    }

    @Test
    @DisplayName("跨层分页按 ts 降序、同 ts 按 id 升序，翻页不重叠")
    void pageUserMemoryOrdersByTsThenId() {
        H2OltpMemoryRepository repo = repository("page_order");
        for (int i = 0; i < 6; i++) {
            repo.saveAtomicMemory(L1AtomicMemory.create(
                    "a" + i, "trace-" + i, "s1", "u1", "custom", "内容" + i, 1000L, "t1"));
        }

        List<UserMemoryRow> first = repo.pageUserMemory("t1", "u1", null, 3, 0);
        List<UserMemoryRow> second = repo.pageUserMemory("t1", "u1", null, 3, 3);

        assertThat(first).extracting(UserMemoryRow::memoryId)
                .containsExactly("a0", "a1", "a2");
        assertThat(second).extracting(UserMemoryRow::memoryId)
                .containsExactly("a3", "a4", "a5");
    }
}
