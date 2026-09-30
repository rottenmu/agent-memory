package com.zimo.module.agentmemory.storage.impl;

import com.zimo.module.agentmemory.model.L1AtomicMemory;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * L1 原子记忆的单表 JDBC 操作。
 *
 * <p>从 {@link H2OltpMemoryRepository} 拆出：该仓储同时承载 L0/L1/L2/L3 四层，
 * 加入租户与分页查询后会突破单文件行数上限，故按「表层」拆分。</p>
 *
 * <p><b>类型范围约束</b>：L1 表混装三类语义不同的数据 —— 用户记忆（{@code user_id} 是真实用户）、
 * 全局记忆（{@code user_id} 列存的是 tenantId）、会话变量（{@code user_id} 是 tenantId）。
 * 因此「取某用户的全部记忆」必须显式排除后两类，否则会串数据。</p>
 */
final class H2AtomicMemoryDao {

    /** 查询列清单（与 {@link #map} 一一对应）。 */
    private static final String COLUMNS =
            "id, trace_id, session_id, user_id, memory_type, content, embedding, ts,"
                    + " tenant_id, importance, access_count, last_access_at";

    /** 「用户长期记忆」类型范围：排除全局与会话变量。 */
    private static final String USER_SCOPE =
            " AND memory_type NOT IN ('" + L1AtomicMemory.TYPE_GLOBAL
                    + "','" + L1AtomicMemory.TYPE_SESSION_VAR + "')";

    /** 单次查询条数硬上限：防误传 {@code Integer.MAX_VALUE} 把整表拉进内存。 */
    private static final int MAX_LIMIT = 10_000;

    private final JdbcTemplate jdbc;
    private final RowMapper<L1AtomicMemory> mapper = this::map;

    /**
     * @param jdbc 共享的 JDBC 模板（由仓储统一提供，保证同一数据源）
     */
    H2AtomicMemoryDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 保存（或覆盖）一条原子记忆。 */
    void save(L1AtomicMemory memory) {
        jdbc.update("""
                        MERGE INTO l1_atomic_memory (id, trace_id, session_id, user_id, memory_type,
                                                     content, embedding, ts, tenant_id, importance,
                                                     access_count, last_access_at)
                        KEY(id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                memory.id(), memory.traceId(), memory.sessionId(), memory.userId(),
                memory.memoryType(), memory.content(), toBytes(memory.embedding()), memory.ts(),
                memory.tenantId(), memory.importance(), memory.accessCount(), memory.lastAccessAt());
    }

    /** 按租户 + id 删除，返回真实影响行数。 */
    int deleteById(String tenantId, String id) {
        return jdbc.update("DELETE FROM l1_atomic_memory WHERE tenant_id = ? AND id = ?",
                tenantId, id);
    }

    /** 按租户 + 归属 + 类型召回（时间降序，同分按 id 升序保证顺序稳定）。 */
    List<L1AtomicMemory> recallByType(
            String tenantId, String owner, String memoryType, int limit, int offset) {
        if (memoryType == null || memoryType.isBlank()) {
            throw new IllegalArgumentException(
                    "memoryType 不能为空；需要「不限类型取用户记忆」请调用 recallByUser"
                            + "（它会排除 global / session_var）");
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM l1_atomic_memory"
                        + " WHERE tenant_id = ? AND user_id = ? AND memory_type = ?"
                        + " ORDER BY ts DESC, id ASC LIMIT ? OFFSET ?",
                mapper, tenantId, owner, memoryType, safeLimit(limit), Math.max(0, offset));
    }

    /** 统计该租户该归属该类型的条数。 */
    long countByType(String tenantId, String owner, String memoryType) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM l1_atomic_memory"
                        + " WHERE tenant_id = ? AND user_id = ? AND memory_type = ?",
                Long.class, tenantId, owner, memoryType);
        return count == null ? 0L : count;
    }

    /** 按租户 + 用户召回「用户长期记忆」分页（排除 global / session_var）。 */
    List<L1AtomicMemory> recallByUser(String tenantId, String userId, int limit, int offset) {
        return jdbc.query("SELECT " + COLUMNS + " FROM l1_atomic_memory"
                        + " WHERE tenant_id = ? AND user_id = ?" + USER_SCOPE
                        + " ORDER BY ts DESC, id ASC LIMIT ? OFFSET ?",
                mapper, tenantId, userId, safeLimit(limit), Math.max(0, offset));
    }

    /** 统计该租户该用户的「用户长期记忆」条数。 */
    long countByUser(String tenantId, String userId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM l1_atomic_memory"
                + " WHERE tenant_id = ? AND user_id = ?" + USER_SCOPE, Long.class, tenantId, userId);
        return count == null ? 0L : count;
    }

    /**
     * 按租户 + 会话召回**会话变量**分页（时间降序）。
     *
     * <p>刻意固定在 {@code session_var} 类型上：同一会话里可能还混有从对话抽取的
     * 其他类型记忆，若不限类型，「会话变量列表」会把它们一起返回。</p>
     */
    List<L1AtomicMemory> recallSessionVars(
            String tenantId, String sessionId, int limit, int offset) {
        return jdbc.query("SELECT " + COLUMNS + " FROM l1_atomic_memory"
                        + " WHERE tenant_id = ? AND session_id = ? AND memory_type = ?"
                        + " ORDER BY ts DESC, id ASC LIMIT ? OFFSET ?",
                mapper, tenantId, sessionId, L1AtomicMemory.TYPE_SESSION_VAR,
                safeLimit(limit), Math.max(0, offset));
    }

    /** 统计该租户该会话的会话变量条数。 */
    long countSessionVars(String tenantId, String sessionId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM l1_atomic_memory"
                        + " WHERE tenant_id = ? AND session_id = ? AND memory_type = ?",
                Long.class, tenantId, sessionId, L1AtomicMemory.TYPE_SESSION_VAR);
        return count == null ? 0L : count;
    }

    /** 按 traceId 查询（不分租户：traceId 全局唯一，用于溯源）。 */
    List<L1AtomicMemory> listByTrace(String traceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM l1_atomic_memory"
                        + " WHERE trace_id = ? ORDER BY ts ASC, id ASC",
                mapper, traceId);
    }

    /** 按租户 + 归属 + 类型删除，返回真实影响行数。 */
    int deleteByType(String tenantId, String owner, String memoryType) {
        return jdbc.update("DELETE FROM l1_atomic_memory"
                        + " WHERE tenant_id = ? AND user_id = ? AND memory_type = ?",
                tenantId, owner, memoryType);
    }

    /** 删除该租户该用户的全部「用户长期记忆」（排除 global / session_var）。 */
    int deleteByUser(String tenantId, String userId) {
        return jdbc.update("DELETE FROM l1_atomic_memory"
                + " WHERE tenant_id = ? AND user_id = ?" + USER_SCOPE, tenantId, userId);
    }

    /** 删除该租户该会话的全部原子记忆。 */
    int deleteBySession(String tenantId, String sessionId) {
        return jdbc.update("DELETE FROM l1_atomic_memory"
                + " WHERE tenant_id = ? AND session_id = ?", tenantId, sessionId);
    }

    /* ---------------- 治理（合并 / 淘汰） ---------------- */

    /**
     * 淘汰候选：该租户的「用户长期记忆」中，最近访问时间早于阈值的条目（最早优先）。
     *
     * <p>「最近访问时间」取 {@code COALESCE(last_access_at, ts)}：从未被召回过的条目
     * {@code last_access_at} 为 {@code NULL}，若直接用该列比较，这些条目会被 SQL 的
     * NULL 语义整体排除在候选之外 —— 而它们恰恰是最该被审视的一批（写进来就没再用过）。</p>
     *
     * <p>固定排除 {@code global} 与 {@code session_var}：全局记忆是租户级配置，会话变量有
     * 独立的终态清理路径，两者都不属于「长期记忆容量治理」的范围。</p>
     *
     * @param tenantId      租户标识
     * @param touchedBefore 时间阈值（毫秒）：早于此值的条目才进入候选
     * @param limit         返回条数
     * @param offset        起始偏移
     * @return 候选条目（最早优先，同值按 id 升序保证顺序稳定）
     */
    List<L1AtomicMemory> recallEvictionCandidates(
            String tenantId, long touchedBefore, int limit, int offset) {
        return jdbc.query("SELECT " + COLUMNS + " FROM l1_atomic_memory"
                        + " WHERE tenant_id = ?" + USER_SCOPE
                        + " AND COALESCE(last_access_at, ts) < ?"
                        + " ORDER BY COALESCE(last_access_at, ts) ASC, id ASC LIMIT ? OFFSET ?",
                mapper, tenantId, touchedBefore, safeLimit(limit), Math.max(0, offset));
    }

    /**
     * 统计该租户的「用户长期记忆」条数（排除全局记忆与会话变量）。
     *
     * <p>用于 {@code /metrics} 的分层分布。刻意带租户条件：指标接口同样属于跨租户
     * 可访问面，若做全库统计，A 租户就能从条数变化推断 B 租户的记忆规模。</p>
     *
     * @param tenantId 租户标识
     * @return 条数；无数据返回 0
     */
    int countByTenant(String tenantId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM l1_atomic_memory"
                + " WHERE tenant_id = ?" + USER_SCOPE, Long.class, tenantId);
        return count == null ? 0 : count.intValue();
    }

    /**
     * 更新一条原子记忆的治理字段（合并时把被合并者的访问计数累加到保留者）。
     *
     * <p>只更新治理字段，不碰 {@code content} / {@code ts} —— 合并保留的是「哪一条」，
     * 而不是「把两条拼成一条」，内容改写会让记忆失去可溯源的原文形态。</p>
     *
     * @param tenantId     租户标识
     * @param id           记忆 id
     * @param importance   新的重要性
     * @param accessCount  新的访问计数
     * @param lastAccessAt 新的最近访问时间；{@code null} 表示保持原值
     * @return 真实影响行数
     */
    int updateGovernance(
            String tenantId, String id, double importance, int accessCount, Long lastAccessAt) {
        if (lastAccessAt == null) {
            return jdbc.update("UPDATE l1_atomic_memory"
                            + " SET importance = ?, access_count = ?"
                            + " WHERE tenant_id = ? AND id = ?",
                    importance, accessCount, tenantId, id);
        }
        return jdbc.update("UPDATE l1_atomic_memory"
                        + " SET importance = ?, access_count = ?, last_access_at = ?"
                        + " WHERE tenant_id = ? AND id = ?",
                importance, accessCount, lastAccessAt, tenantId, id);
    }

    /** 删除指定 id 集合的原子记忆（仅限该租户）。 */
    int deleteByIds(String tenantId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Object[] args = new Object[ids.size() + 1];
        args[0] = tenantId;
        for (int index = 0; index < ids.size(); index++) {
            args[index + 1] = ids.get(index);
        }
        return jdbc.update("DELETE FROM l1_atomic_memory"
                + " WHERE tenant_id = ? AND id IN (" + placeholders + ")", args);
    }

    /* ---------------- 映射与工具 ---------------- */

    private L1AtomicMemory map(ResultSet rs, int rowNum) throws SQLException {
        return new L1AtomicMemory(
                rs.getString("id"),
                rs.getString("trace_id"),
                rs.getString("session_id"),
                rs.getString("user_id"),
                rs.getString("memory_type"),
                rs.getString("content"),
                fromBytes(rs.getBytes("embedding")),
                rs.getLong("ts"),
                rs.getString("tenant_id"),
                readDouble(rs, "importance", L1AtomicMemory.DEFAULT_IMPORTANCE),
                rs.getInt("access_count"),
                readLong(rs, "last_access_at"));
    }

    /** 兼容旧库：补列前写入的行该字段可能为 NULL，读回时回落默认值。 */
    private static double readDouble(ResultSet rs, String column, double fallback)
            throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? fallback : value;
    }

    private static Long readLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static int safeLimit(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    private static byte[] toBytes(float[] vector) {
        if (vector == null) {
            return null;
        }
        byte[] bytes = new byte[vector.length * 4];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return bytes;
    }

    private static float[] fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        float[] vector = new float[bytes.length / 4];
        for (int index = 0; index < vector.length; index++) {
            vector[index] = buffer.getFloat();
        }
        return vector;
    }
}
