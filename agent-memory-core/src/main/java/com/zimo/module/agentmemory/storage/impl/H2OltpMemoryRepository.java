package com.zimo.module.agentmemory.storage.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zimo.module.agentmemory.model.L0RawLog;
import com.zimo.module.agentmemory.model.L1AtomicMemory;
import com.zimo.module.agentmemory.model.L2SceneBlock;
import com.zimo.module.agentmemory.model.L3Persona;
import com.zimo.module.agentmemory.storage.OltpMemoryRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * H2 MVStore 实现的 OLTP 记忆仓储。
 *
 * <p>表结构遵循硬性约束：无外键、下划线字段命名；L3 画像叠加 Caffeine
 * 进程缓存（会话启动优先加载画像）。运行时记忆查询只走本仓储（H2），
 * OLAP 分析不经过本实现。</p>
 *
 * @see <a href="https://h2database.com">H2 Database</a>
 */
public class H2OltpMemoryRepository implements OltpMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(H2OltpMemoryRepository.class);

    private final JdbcTemplate jdbc;
    /** L3 画像进程缓存：key = userId:personaType。 */
    private final Cache<String, L3Persona> personaCache;

    public H2OltpMemoryRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.personaCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofHours(2))
                .build();
        initSchema();
    }

    /** 初始化四层记忆表结构（幂等，无外键，下划线命名）。 */
    private void initSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS l0_raw_log (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  trace_id VARCHAR(64) NOT NULL,
                  session_id VARCHAR(64) NOT NULL,
                  user_id VARCHAR(64),
                  ts BIGINT NOT NULL,
                  role VARCHAR(16) NOT NULL,
                  content CLOB NOT NULL,
                  tokens INT,
                  meta_json CLOB,
                  source VARCHAR(32)
                )""");
        // 兼容旧库：已有表缺 source 列时补列
        jdbc.execute("ALTER TABLE l0_raw_log ADD COLUMN IF NOT EXISTS source VARCHAR(32)");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS l1_atomic_memory (
                  id VARCHAR(32) PRIMARY KEY,
                  trace_id VARCHAR(64) NOT NULL,
                  session_id VARCHAR(64) NOT NULL,
                  user_id VARCHAR(64),
                  memory_type VARCHAR(32) NOT NULL,
                  content CLOB NOT NULL,
                  embedding BLOB,
                  ts BIGINT NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS l2_scene_block (
                  id VARCHAR(32) PRIMARY KEY,
                  session_id VARCHAR(64) NOT NULL,
                  scene_name VARCHAR(64),
                  summary CLOB,
                  start_ts BIGINT,
                  end_ts BIGINT,
                  l1_ids CLOB,
                  ts BIGINT NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS l3_persona (
                  id VARCHAR(32) PRIMARY KEY,
                  user_id VARCHAR(64) NOT NULL,
                  persona_type VARCHAR(32) NOT NULL,
                  content CLOB NOT NULL,
                  version INT DEFAULT 1,
                  updated_ts BIGINT NOT NULL
                )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_l0_trace ON l0_raw_log(trace_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_l0_session ON l0_raw_log(session_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_l1_user_type ON l1_atomic_memory(user_id, memory_type)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_l2_session ON l2_scene_block(session_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_l3_user ON l3_persona(user_id)");
        log.info("H2 四层记忆表结构初始化完成");
    }

    /* ================= L0 原始日志 ================= */

    @Override
    public void saveRawLog(L0RawLog logEntry) {
        jdbc.update("""
                        INSERT INTO l0_raw_log (trace_id, session_id, user_id, ts, role, content, tokens, meta_json, source)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                logEntry.traceId(),
                logEntry.sessionId(),
                logEntry.userId(),
                logEntry.ts(),
                logEntry.role(),
                logEntry.content(),
                logEntry.tokens(),
                logEntry.metaJson(),
                logEntry.source());
    }

    @Override
    public List<L0RawLog> listRawLogsByTrace(String traceId) {
        return jdbc.query("""
                        SELECT id, trace_id, session_id, user_id, ts, role, content, tokens, meta_json, source
                        FROM l0_raw_log WHERE trace_id = ? ORDER BY ts ASC""",
                this::mapRawLog, traceId);
    }

    @Override
    public List<L0RawLog> listRawLogsBySession(String sessionId) {
        return jdbc.query("""
                        SELECT id, trace_id, session_id, user_id, ts, role, content, tokens, meta_json, source
                        FROM l0_raw_log WHERE session_id = ? ORDER BY ts ASC""",
                this::mapRawLog, sessionId);
    }

    @Override
    public List<L0RawLog> listRawLogsPage(int offset, int limit) {
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(limit, 10_000));
        return jdbc.query("""
                        SELECT id, trace_id, session_id, user_id, ts, role, content, tokens, meta_json, source
                        FROM l0_raw_log ORDER BY id ASC LIMIT ? OFFSET ?""",
                this::mapRawLog, safeLimit, safeOffset);
    }

    /** 按自增 id 增量拉取原始日志（ETL 游标用，id 升序）。 */
    public List<L0RawLog> listRawLogsSince(long afterId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 10_000));
        return jdbc.query("""
                        SELECT id, trace_id, session_id, user_id, ts, role, content, tokens, meta_json, source
                        FROM l0_raw_log WHERE id > ? ORDER BY id ASC LIMIT ?""",
                this::mapRawLog, afterId, safeLimit);
    }

    /* ================= L1 原子记忆 ================= */

    @Override
    public void saveAtomicMemory(L1AtomicMemory memory) {
        jdbc.update("""
                        MERGE INTO l1_atomic_memory (id, trace_id, session_id, user_id, memory_type, content, embedding, ts)
                        KEY(id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                memory.id(),
                memory.traceId(),
                memory.sessionId(),
                memory.userId(),
                memory.memoryType(),
                memory.content(),
                toBytes(memory.embedding()),
                memory.ts());
    }

    @Override
    public void deleteAtomicMemory(String id) {
        jdbc.update("DELETE FROM l1_atomic_memory WHERE id = ?", id);
    }

    @Override
    public List<L1AtomicMemory> recallAtomicByType(String userId, String memoryType, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return jdbc.query("""
                        SELECT id, trace_id, session_id, user_id, memory_type, content, embedding, ts
                        FROM l1_atomic_memory
                        WHERE user_id = ? AND memory_type = ?
                        ORDER BY ts DESC LIMIT ?""",
                this::mapAtomicMemory, userId, memoryType, safeLimit);
    }

    @Override
    public List<L1AtomicMemory> recallAtomicBySession(String sessionId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return jdbc.query("""
                        SELECT id, trace_id, session_id, user_id, memory_type, content, embedding, ts
                        FROM l1_atomic_memory
                        WHERE session_id = ? ORDER BY ts DESC LIMIT ?""",
                this::mapAtomicMemory, sessionId, safeLimit);
    }

    @Override
    public List<L1AtomicMemory> listAtomicByTrace(String traceId) {
        return jdbc.query("""
                        SELECT id, trace_id, session_id, user_id, memory_type, content, embedding, ts
                        FROM l1_atomic_memory WHERE trace_id = ? ORDER BY ts ASC""",
                this::mapAtomicMemory, traceId);
    }

    /* ================= L2 场景块 ================= */

    @Override
    public void saveSceneBlock(L2SceneBlock block) {
        jdbc.update("""
                        MERGE INTO l2_scene_block (id, session_id, scene_name, summary, start_ts, end_ts, l1_ids, ts)
                        KEY(id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                block.id(),
                block.sessionId(),
                block.sceneName(),
                block.summary(),
                block.startTs(),
                block.endTs(),
                L1AtomicMemory.l1IdsToJson(block.l1Ids()),
                block.ts());
    }

    @Override
    public L2SceneBlock recallSceneBySession(String sessionId) {
        List<L2SceneBlock> scenes = jdbc.query("""
                        SELECT id, session_id, scene_name, summary, start_ts, end_ts, l1_ids, ts
                        FROM l2_scene_block WHERE session_id = ? ORDER BY ts DESC LIMIT 1""",
                this::mapSceneBlock, sessionId);
        return scenes.isEmpty() ? null : scenes.get(0);
    }

    @Override
    public List<L2SceneBlock> listScenesBySession(String sessionId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.query("""
                        SELECT id, session_id, scene_name, summary, start_ts, end_ts, l1_ids, ts
                        FROM l2_scene_block WHERE session_id = ? ORDER BY ts DESC LIMIT ?""",
                this::mapSceneBlock, sessionId, safeLimit);
    }

    /* ================= L3 画像（Caffeine 缓存） ================= */

    @Override
    public void savePersona(L3Persona persona) {
        jdbc.update("""
                        MERGE INTO l3_persona (id, user_id, persona_type, content, version, updated_ts)
                        KEY(id) VALUES (?, ?, ?, ?, ?, ?)""",
                persona.id(),
                persona.userId(),
                persona.personaType(),
                persona.content(),
                persona.version(),
                persona.updatedTs());
        personaCache.put(cacheKey(persona.userId(), persona.personaType()), persona);
    }

    @Override
    public L3Persona getPersona(String userId, String personaType) {
        String key = cacheKey(userId, personaType);
        L3Persona cached = personaCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        List<L3Persona> list = jdbc.query("""
                        SELECT id, user_id, persona_type, content, version, updated_ts
                        FROM l3_persona WHERE user_id = ? AND persona_type = ? ORDER BY updated_ts DESC LIMIT 1""",
                this::mapPersona, userId, personaType);
        if (list.isEmpty()) {
            return null;
        }
        personaCache.put(key, list.get(0));
        return list.get(0);
    }

    @Override
    public void deletePersona(String userId, String personaType) {
        jdbc.update("DELETE FROM l3_persona WHERE user_id = ? AND persona_type = ?", userId, personaType);
        personaCache.invalidate(cacheKey(userId, personaType));
    }

    @Override
    public List<L3Persona> listPersonas(String userId) {
        return jdbc.query("""
                        SELECT id, user_id, persona_type, content, version, updated_ts
                        FROM l3_persona WHERE user_id = ? ORDER BY updated_ts DESC""",
                this::mapPersona, userId);
    }

    /* ================= 钻取召回（L3 → L2 → L1 → L0） ================= */

    @Override
    public List<L0RawLog> drillDownToRawLog(String traceId) {
        return listRawLogsByTrace(traceId);
    }

    /* ================= 映射与工具 ================= */

    private L0RawLog mapRawLog(ResultSet rs, int rowNum) throws SQLException {
        return new L0RawLog(
                rs.getLong("id"),
                rs.getString("trace_id"),
                rs.getString("session_id"),
                rs.getString("user_id"),
                rs.getLong("ts"),
                rs.getString("role"),
                rs.getString("content"),
                (Integer) rs.getObject("tokens"),
                rs.getString("meta_json"),
                rs.getString("source"));
    }

    private L1AtomicMemory mapAtomicMemory(ResultSet rs, int rowNum) throws SQLException {
        return new L1AtomicMemory(
                rs.getString("id"),
                rs.getString("trace_id"),
                rs.getString("session_id"),
                rs.getString("user_id"),
                rs.getString("memory_type"),
                rs.getString("content"),
                fromBytes(rs.getBytes("embedding")),
                rs.getLong("ts"));
    }

    private L2SceneBlock mapSceneBlock(ResultSet rs, int rowNum) throws SQLException {
        return new L2SceneBlock(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("scene_name"),
                rs.getString("summary"),
                rs.getLong("start_ts"),
                rs.getLong("end_ts"),
                L2SceneBlock.l1IdsFromJson(rs.getString("l1_ids")),
                rs.getLong("ts"));
    }

    private L3Persona mapPersona(ResultSet rs, int rowNum) throws SQLException {
        return new L3Persona(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("persona_type"),
                rs.getString("content"),
                rs.getInt("version"),
                rs.getLong("updated_ts"));
    }

    private static String cacheKey(String userId, String personaType) {
        return userId + ":" + personaType;
    }

    private static byte[] toBytes(float[] vector) {
        if (vector == null) {
            return null;
        }
        byte[] bytes = new byte[vector.length * 4];
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return bytes;
    }

    private static float[] fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        float[] vector = new float[bytes.length / 4];
        for (int index = 0; index < vector.length; index++) {
            vector[index] = buffer.getFloat();
        }
        return vector;
    }
    @Override
    public long countL0Total() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM l0_raw_log", Long.class);
        return c == null ? 0L : c;
    }

    @Override
    public long countL0Today() {
        long from = startOfToday();
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM l0_raw_log WHERE ts >= ?", Long.class, from);
        return c == null ? 0L : c;
    }

    private static long startOfToday() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
