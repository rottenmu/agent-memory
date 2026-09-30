package com.zimo.module.agentmemory.storage.impl;

import com.zimo.module.agentmemory.model.L0RawLog;
import com.zimo.module.agentmemory.model.L1AtomicMemory;
import com.zimo.module.agentmemory.model.L2SceneBlock;
import com.zimo.module.agentmemory.model.L3Persona;
import com.zimo.module.agentmemory.model.UserMemoryRow;
import com.zimo.module.agentmemory.storage.OltpMemoryRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * H2 MVStore 实现的 OLTP 记忆仓储。
 *
 * <p>表结构遵循硬性约束：无外键、下划线字段命名。运行时记忆查询只走本仓储（H2），
 * OLAP 分析不经过本实现。</p>
 *
 * <p>本类只保留「建表与迁移、L0 原始日志、L2 场景块」三部分；L1 原子记忆、L3 画像与
 * 跨层分页已按表层拆到 {@link H2AtomicMemoryDao} / {@link H2PersonaDao} /
 * {@link H2UserMemoryPageDao}，避免单文件因四层查询堆叠而突破行数上限。</p>
 *
 * <p><b>迁移约定</b>：库是 H2 MVStore 文件库（{@code ./data/agent-memory}），已存在旧库。
 * 加列一律用 {@code ADD COLUMN IF NOT EXISTS} + {@code DEFAULT} 回填，**不重建表、不迁移
 * 文件**；{@code created_at} 为空的旧画像回填为 {@code updated_ts}。</p>
 *
 * @see <a href="https://h2database.com">H2 Database</a>
 */
public class H2OltpMemoryRepository implements OltpMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(H2OltpMemoryRepository.class);

    /** 缺省租户：既有数据与未显式指定租户的调用统一落这里。 */
    private static final String DEFAULT_TENANT = L1AtomicMemory.DEFAULT_TENANT;

    private final JdbcTemplate jdbc;
    private final H2AtomicMemoryDao atomicDao;
    private final H2PersonaDao personaDao;
    private final H2UserMemoryPageDao pageQuery;
    /** 记忆审计表（memory_span）：观测/运维事实，与四层业务记忆表分开维护。 */
    private final H2MemorySpanDao spanDao;

    public H2OltpMemoryRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.atomicDao = new H2AtomicMemoryDao(this.jdbc);
        this.personaDao = new H2PersonaDao(this.jdbc);
        this.pageQuery = new H2UserMemoryPageDao(this.jdbc);
        // 审计表由 DAO 自建（幂等）：它的表结构与四层记忆表无关，没必要让本类的 initSchema 承担
        this.spanDao = new H2MemorySpanDao(this.jdbc);
        initSchema();
    }

    /** 初始化四层记忆表结构（幂等，无外键，下划线命名）。 */
    private void initSchema() {
        initRawLogSchema();
        initAtomicMemorySchema();
        initSceneBlockSchema();
        initPersonaSchema();
        initIndexes();
        backfillLegacyRows();
        log.info("H2 四层记忆表结构初始化完成");
    }

    private void initRawLogSchema() {
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
        jdbc.execute("ALTER TABLE l0_raw_log ADD COLUMN IF NOT EXISTS source VARCHAR(32)");
    }

    private void initAtomicMemorySchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS l1_atomic_memory (
                  id VARCHAR(32) PRIMARY KEY,
                  trace_id VARCHAR(64) NOT NULL,
                  session_id VARCHAR(64) NOT NULL,
                  user_id VARCHAR(64),
                  memory_type VARCHAR(32) NOT NULL,
                  content CLOB NOT NULL,
                  embedding BLOB,
                  ts BIGINT NOT NULL,
                  tenant_id VARCHAR(64) DEFAULT 'default',
                  importance DOUBLE DEFAULT 0.5,
                  access_count INT DEFAULT 0,
                  last_access_at BIGINT
                )""");
        jdbc.execute("ALTER TABLE l1_atomic_memory ADD COLUMN IF NOT EXISTS"
                + " tenant_id VARCHAR(64) DEFAULT 'default'");
        jdbc.execute("ALTER TABLE l1_atomic_memory ADD COLUMN IF NOT EXISTS"
                + " importance DOUBLE DEFAULT 0.5");
        jdbc.execute("ALTER TABLE l1_atomic_memory ADD COLUMN IF NOT EXISTS"
                + " access_count INT DEFAULT 0");
        jdbc.execute("ALTER TABLE l1_atomic_memory ADD COLUMN IF NOT EXISTS last_access_at BIGINT");
    }

    private void initSceneBlockSchema() {
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
    }

    private void initPersonaSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS l3_persona (
                  id VARCHAR(32) PRIMARY KEY,
                  user_id VARCHAR(64) NOT NULL,
                  persona_type VARCHAR(32) NOT NULL,
                  content CLOB NOT NULL,
                  version INT DEFAULT 1,
                  updated_ts BIGINT NOT NULL,
                  tenant_id VARCHAR(64) DEFAULT 'default',
                  importance DOUBLE DEFAULT 0.5,
                  access_count INT DEFAULT 0,
                  last_access_at BIGINT,
                  created_at BIGINT
                )""");
        jdbc.execute("ALTER TABLE l3_persona ADD COLUMN IF NOT EXISTS"
                + " tenant_id VARCHAR(64) DEFAULT 'default'");
        jdbc.execute("ALTER TABLE l3_persona ADD COLUMN IF NOT EXISTS"
                + " importance DOUBLE DEFAULT 0.5");
        jdbc.execute("ALTER TABLE l3_persona ADD COLUMN IF NOT EXISTS access_count INT DEFAULT 0");
        jdbc.execute("ALTER TABLE l3_persona ADD COLUMN IF NOT EXISTS last_access_at BIGINT");
        jdbc.execute("ALTER TABLE l3_persona ADD COLUMN IF NOT EXISTS created_at BIGINT");
    }

    /**
     * 索引改成「租户 + 用户」复合前缀：所有查询都强制带租户条件，
     * 旧的 {@code (user_id, memory_type)} / {@code (user_id)} 单列索引在多租户下已无法
     * 作为有效前缀使用，于是直接替换而非保留冗余索引。
     */
    private void initIndexes() {
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_l0_trace ON l0_raw_log(trace_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_l0_session ON l0_raw_log(session_id)");
        jdbc.execute("DROP INDEX IF EXISTS idx_l1_user_type");
        jdbc.execute("DROP INDEX IF EXISTS idx_l3_user");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_l1_tenant_user_type"
                + " ON l1_atomic_memory(tenant_id, user_id, memory_type)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_l1_tenant_session"
                + " ON l1_atomic_memory(tenant_id, session_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_l2_session ON l2_scene_block(session_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_l3_tenant_user"
                + " ON l3_persona(tenant_id, user_id)");
    }

    /**
     * 回填旧库数据：{@code ADD COLUMN ... DEFAULT} 对既有行未必生效，
     * 且旧画像没有创建时间概念，一并补齐，避免读回时出现 {@code null} 语义。
     */
    private void backfillLegacyRows() {
        jdbc.update("UPDATE l1_atomic_memory SET tenant_id = ? WHERE tenant_id IS NULL",
                DEFAULT_TENANT);
        jdbc.update("UPDATE l1_atomic_memory SET importance = 0.5 WHERE importance IS NULL");
        jdbc.update("UPDATE l1_atomic_memory SET access_count = 0 WHERE access_count IS NULL");
        jdbc.update("UPDATE l3_persona SET tenant_id = ? WHERE tenant_id IS NULL", DEFAULT_TENANT);
        jdbc.update("UPDATE l3_persona SET importance = 0.5 WHERE importance IS NULL");
        jdbc.update("UPDATE l3_persona SET access_count = 0 WHERE access_count IS NULL");
        jdbc.update("UPDATE l3_persona SET created_at = updated_ts WHERE created_at IS NULL");
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

    @Override
    public List<L0RawLog> listRawLogsSince(long afterId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 10_000));
        return jdbc.query("""
                        SELECT id, trace_id, session_id, user_id, ts, role, content, tokens, meta_json, source
                        FROM l0_raw_log WHERE id > ? ORDER BY id ASC LIMIT ?""",
                this::mapRawLog, afterId, safeLimit);
    }

    /* ================= L1 原子记忆（委托） ================= */

    @Override
    public void saveAtomicMemory(L1AtomicMemory memory) {
        atomicDao.save(memory);
    }

    @Override
    public int deleteAtomicMemory(String tenantId, String id) {
        return atomicDao.deleteById(tenantId, id);
    }

    @Override
    public List<L1AtomicMemory> recallAtomicByType(
            String tenantId, String owner, String memoryType, int limit, int offset) {
        return atomicDao.recallByType(tenantId, owner, memoryType, limit, offset);
    }

    @Override
    public long countAtomicByType(String tenantId, String owner, String memoryType) {
        return atomicDao.countByType(tenantId, owner, memoryType);
    }

    @Override
    public List<L1AtomicMemory> recallAtomicByUser(
            String tenantId, String userId, int limit, int offset) {
        return atomicDao.recallByUser(tenantId, userId, limit, offset);
    }

    @Override
    public long countAtomicByUser(String tenantId, String userId) {
        return atomicDao.countByUser(tenantId, userId);
    }

    @Override
    public List<L1AtomicMemory> recallSessionVars(
            String tenantId, String sessionId, int limit, int offset) {
        return atomicDao.recallSessionVars(tenantId, sessionId, limit, offset);
    }

    @Override
    public long countSessionVars(String tenantId, String sessionId) {
        return atomicDao.countSessionVars(tenantId, sessionId);
    }

    @Override
    public List<L1AtomicMemory> listAtomicByTrace(String traceId) {
        return atomicDao.listByTrace(traceId);
    }

    @Override
    public int deleteAtomicByType(String tenantId, String owner, String memoryType) {
        return atomicDao.deleteByType(tenantId, owner, memoryType);
    }

    @Override
    public int deleteAtomicByUser(String tenantId, String userId) {
        return atomicDao.deleteByUser(tenantId, userId);
    }

    @Override
    public int deleteAtomicBySession(String tenantId, String sessionId) {
        return atomicDao.deleteBySession(tenantId, sessionId);
    }

    @Override
    public List<L1AtomicMemory> recallEvictionCandidates(
            String tenantId, long touchedBefore, int limit, int offset) {
        return atomicDao.recallEvictionCandidates(tenantId, touchedBefore, limit, offset);
    }

    @Override
    public int updateAtomicGovernance(
            String tenantId, String id, double importance, int accessCount, Long lastAccessAt) {
        return atomicDao.updateGovernance(tenantId, id, importance, accessCount, lastAccessAt);
    }

    @Override
    public int deleteAtomicByIds(String tenantId, List<String> ids) {
        return atomicDao.deleteByIds(tenantId, ids);
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

    /* ================= L3 画像（委托） ================= */

    @Override
    public void savePersona(L3Persona persona) {
        personaDao.save(persona);
    }

    @Override
    public L3Persona getPersona(String tenantId, String userId, String personaType) {
        return personaDao.get(tenantId, userId, personaType);
    }

    @Override
    public int deletePersona(String tenantId, String userId, String personaType) {
        return personaDao.deleteByType(tenantId, userId, personaType);
    }

    @Override
    public int deletePersonaById(String tenantId, String id) {
        return personaDao.deleteById(tenantId, id);
    }

    @Override
    public int deletePersonasByUser(String tenantId, String userId) {
        return personaDao.deleteByUser(tenantId, userId);
    }

    /* ================= 用户长期记忆合并分页（委托） ================= */

    @Override
    public List<UserMemoryRow> pageUserMemory(
            String tenantId, String userId, String category, int limit, int offset) {
        return pageQuery.page(tenantId, userId, category, limit, offset);
    }

    @Override
    public List<UserMemoryRow> listUserMemoryByIds(
            String tenantId, String userId, List<String> ids) {
        return pageQuery.listByIds(tenantId, userId, ids);
    }

    @Override
    public long countUserMemory(String tenantId, String userId, String category) {
        return pageQuery.count(tenantId, userId, category);
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

    @Override
    public int countAtomicByTenant(String tenantId) {
        return atomicDao.countByTenant(tenantId);
    }

    @Override
    public int countPersonaByTenant(String tenantId) {
        return personaDao.countByTenant(tenantId);
    }

    /* ---------------- 记忆审计（memory_span） ---------------- */

    @Override
    public void saveMemorySpan(com.zimo.module.agentmemory.engine.MemorySpanRecord record) {
        spanDao.save(record);
    }

    @Override
    public List<com.zimo.module.agentmemory.engine.MemorySpanRecord> listMemorySpansByTrace(
            String traceId) {
        return spanDao.listByTrace(traceId);
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
