package com.zimo.agent.memory.storage.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zimo.agent.memory.model.L3Persona;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * L3 画像的单表 JDBC 操作与进程缓存。
 *
 * <p>会话启动时优先命中缓存，避免每轮对话都打一次库。</p>
 *
 * <p><b>缓存键必须带租户</b>：早期键为 {@code userId:personaType}，同一 userId 在不同租户
 * 会命中同一缓存项 —— 这正是租户隔离失效（缺陷 D8b）在缓存层的表现。</p>
 */
final class H2PersonaDao {

    /** 查询列清单（与 {@link #map} 一一对应）。 */
    private static final String COLUMNS =
            "id, user_id, persona_type, content, version, updated_ts,"
                    + " tenant_id, importance, access_count, last_access_at, created_at";

    private final JdbcTemplate jdbc;
    private final RowMapper<L3Persona> mapper = this::map;
    /** L3 画像进程缓存：key = tenantId:userId:personaType。 */
    private final Cache<String, L3Persona> personaCache;

    /**
     * @param jdbc 共享的 JDBC 模板（由仓储统一提供，保证同一数据源）
     */
    H2PersonaDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.personaCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofHours(2))
                .build();
    }

    /** 保存（或覆盖）画像并刷新缓存。 */
    void save(L3Persona persona) {
        jdbc.update("""
                        MERGE INTO l3_persona (id, user_id, persona_type, content, version,
                                               updated_ts, tenant_id, importance, access_count,
                                               last_access_at, created_at)
                        KEY(id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                persona.id(), persona.userId(), persona.personaType(), persona.content(),
                persona.version(), persona.updatedTs(), persona.tenantId(), persona.importance(),
                persona.accessCount(), persona.lastAccessAt(), persona.createdAt());
        personaCache.put(cacheKey(persona.tenantId(), persona.userId(), persona.personaType()),
                persona);
    }

    /** 按租户 + 用户 + 类型读取（优先缓存）。 */
    L3Persona get(String tenantId, String userId, String personaType) {
        String key = cacheKey(tenantId, userId, personaType);
        L3Persona cached = personaCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        List<L3Persona> list = jdbc.query("SELECT " + COLUMNS + " FROM l3_persona"
                        + " WHERE tenant_id = ? AND user_id = ? AND persona_type = ?"
                        + " ORDER BY updated_ts DESC LIMIT 1",
                mapper, tenantId, userId, personaType);
        if (list.isEmpty()) {
            return null;
        }
        personaCache.put(key, list.get(0));
        return list.get(0);
    }

    /** 按租户 + 用户 + 类型删除并失效缓存，返回真实影响行数。 */
    int deleteByType(String tenantId, String userId, String personaType) {
        int affected = jdbc.update("DELETE FROM l3_persona"
                + " WHERE tenant_id = ? AND user_id = ? AND persona_type = ?",
                tenantId, userId, personaType);
        personaCache.invalidate(cacheKey(tenantId, userId, personaType));
        return affected;
    }

    /**
     * 按租户 + 画像 id 删除，返回真实影响行数。
     *
     * <p>先查后删：只有知道 {@code user_id} 与 {@code persona_type} 才能失效对应缓存项，
     * 否则会出现「库已删、缓存仍在」的脏读。</p>
     */
    int deleteById(String tenantId, String id) {
        List<String[]> keys = jdbc.query(
                "SELECT user_id, persona_type FROM l3_persona WHERE tenant_id = ? AND id = ?",
                (rs, rowNum) -> new String[] {rs.getString("user_id"), rs.getString("persona_type")},
                tenantId, id);
        if (keys.isEmpty()) {
            return 0;
        }
        int affected = jdbc.update(
                "DELETE FROM l3_persona WHERE tenant_id = ? AND id = ?", tenantId, id);
        for (String[] key : keys) {
            personaCache.invalidate(cacheKey(tenantId, key[0], key[1]));
        }
        return affected;
    }

    /** 删除该租户该用户的全部画像，并清理其全部缓存项。 */
    int deleteByUser(String tenantId, String userId) {
        int affected = jdbc.update(
                "DELETE FROM l3_persona WHERE tenant_id = ? AND user_id = ?", tenantId, userId);
        String prefix = tenantId + ":" + userId + ":";
        personaCache.asMap().keySet().removeIf(key -> key.startsWith(prefix));
        return affected;
    }

    /**
     * 统计该租户的画像条数（用于 {@code /metrics} 的分层分布）。
     *
     * <p>带租户条件的原因与 L1 侧一致：指标接口不得成为跨租户的规模探针。</p>
     *
     * @param tenantId 租户标识
     * @return 条数；无数据返回 0
     */
    int countByTenant(String tenantId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM l3_persona WHERE tenant_id = ?", Long.class, tenantId);
        return count == null ? 0 : count.intValue();
    }

    /* ---------------- 映射与工具 ---------------- */

    private L3Persona map(ResultSet rs, int rowNum) throws SQLException {
        return new L3Persona(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("persona_type"),
                rs.getString("content"),
                rs.getInt("version"),
                rs.getLong("updated_ts"),
                rs.getString("tenant_id"),
                readDouble(rs, "importance", L3Persona.DEFAULT_IMPORTANCE),
                rs.getInt("access_count"),
                readLong(rs, "last_access_at"),
                rs.getLong("created_at"));
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

    private static String cacheKey(String tenantId, String userId, String personaType) {
        return tenantId + ":" + userId + ":" + personaType;
    }
}
