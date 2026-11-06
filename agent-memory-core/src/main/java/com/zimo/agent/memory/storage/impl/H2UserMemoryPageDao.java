package com.zimo.agent.memory.storage.impl;

import com.zimo.agent.memory.model.L1AtomicMemory;
import com.zimo.agent.memory.model.UserMemoryRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 「用户长期记忆」跨层分页查询：L3 画像 ∪ L1 原子记忆。
 *
 * <p>为什么要在 SQL 层合并：用户记忆天然来自两张表。早期实现是「两张表各取一页
 * 再在内存合并排序」，于是必须先给每张表定一个硬上限（500），第 501 条起永远不可见
 * 且无任何提示 —— 这就是缺陷 D10。改为 {@code UNION ALL} 后由数据库负责排序与切片，
 * {@code LIMIT / OFFSET} 的语义才真正成立。</p>
 *
 * <p>排序为 {@code ts DESC, memory_id ASC}：末尾按 id 兜底，避免同毫秒记录在翻页时
 * 顺序漂移（否则两页会出现重叠或漏项）。</p>
 */
final class H2UserMemoryPageDao {

    /** 单次查询条数硬上限：防误传 {@link Integer#MAX_VALUE}。 */
    private static final int MAX_LIMIT = 10_000;

    /** 「用户长期记忆」类型范围：排除全局与会话变量。 */
    private static final String USER_SCOPE =
            " AND memory_type NOT IN ('" + L1AtomicMemory.TYPE_GLOBAL
                    + "','" + L1AtomicMemory.TYPE_SESSION_VAR + "')";

    private static final String L3_SELECT =
            "  SELECT id AS memory_id, persona_type AS category, content AS content,"
                    + "    updated_ts AS ts, version AS version, importance AS importance,"
                    + "    access_count AS access_count, last_access_at AS last_access_at,"
                    + "    'l3' AS layer"
                    + "  FROM l3_persona WHERE tenant_id = ? AND user_id = ?";

    private static final String L1_SELECT =
            "  SELECT id AS memory_id, memory_type AS category, content AS content,"
                    + "    ts AS ts, CAST(NULL AS INT) AS version, importance AS importance,"
                    + "    access_count AS access_count, last_access_at AS last_access_at,"
                    + "    'l1' AS layer"
                    + "  FROM l1_atomic_memory WHERE tenant_id = ? AND user_id = ?" + USER_SCOPE;

    private final JdbcTemplate jdbc;
    private final RowMapper<UserMemoryRow> mapper = this::map;

    /**
     * @param jdbc 共享的 JDBC 模板（由仓储统一提供，保证同一数据源）
     */
    H2UserMemoryPageDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 分页查询用户长期记忆（时间降序，同分按 id 升序）。 */
    List<UserMemoryRow> page(
            String tenantId, String userId, String category, int limit, int offset) {
        boolean filtered = hasText(category);
        String l3Filter = filtered ? " AND persona_type = ?" : "";
        String l1Filter = filtered ? " AND memory_type = ?" : "";
        String sql = "SELECT memory_id, category, content, ts, version, importance,"
                + " access_count, last_access_at, layer FROM ("
                + L3_SELECT + l3Filter
                + " UNION ALL"
                + L1_SELECT + l1Filter
                + ") unified ORDER BY ts DESC, memory_id ASC LIMIT ? OFFSET ?";
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(userId);
        if (filtered) {
            args.add(category);
        }
        args.add(tenantId);
        args.add(userId);
        if (filtered) {
            args.add(category);
        }
        args.add(Math.max(1, Math.min(limit, MAX_LIMIT)));
        args.add(Math.max(0, offset));
        return jdbc.query(sql, mapper, args.toArray());
    }

    /** 统计用户长期记忆总数（与 {@link #page} 同口径）。 */
    long count(String tenantId, String userId, String category) {
        boolean filtered = hasText(category);
        String l3Filter = filtered ? " AND persona_type = ?" : "";
        String l1Filter = filtered ? " AND memory_type = ?" : "";
        String sql = "SELECT (SELECT COUNT(*) FROM l3_persona"
                + " WHERE tenant_id = ? AND user_id = ?" + l3Filter + ")"
                + " + (SELECT COUNT(*) FROM l1_atomic_memory"
                + " WHERE tenant_id = ? AND user_id = ?" + USER_SCOPE + l1Filter + ")";
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(userId);
        if (filtered) {
            args.add(category);
        }
        args.add(tenantId);
        args.add(userId);
        if (filtered) {
            args.add(category);
        }
        Long total = jdbc.queryForObject(sql, Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    /**
     * 按 id 集合查询用户长期记忆（L3 ∪ L1）。
     *
     * <p>为什么不在调用方「分页扫全量再按 id 过滤」：扫描有上限，超限即静默漏查
     * （与缺陷 D10 同源）。这里把 id 条件下推到 SQL，不做任何隐式截断。</p>
     *
     * <p>SQL 参数长度受 {@code ids} 规模约束，调用方需先做条数与去重校验。</p>
     *
     * @param tenantId 租户标识（隔离维度，必填）
     * @param userId   用户标识（限定在单个用户范围内，避免越权读他人记忆）
     * @param ids      记忆主键集合（已去重、非空）
     * @return 命中行（时间降序，同分按 id 升序）；未命中的 id 不占位
     */
    List<UserMemoryRow> listByIds(String tenantId, String userId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String l3Filter = " AND id IN (" + placeholders + ")";
        String l1Filter = " AND id IN (" + placeholders + ")";
        String sql = "SELECT memory_id, category, content, ts, version, importance,"
                + " access_count, last_access_at, layer FROM ("
                + L3_SELECT + l3Filter
                + " UNION ALL"
                + L1_SELECT + l1Filter
                + ") unified ORDER BY ts DESC, memory_id ASC";
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(userId);
        args.addAll(ids);
        args.add(tenantId);
        args.add(userId);
        args.addAll(ids);
        return jdbc.query(sql, mapper, args.toArray());
    }

    /* ---------------- 映射与工具 ---------------- */

    private UserMemoryRow map(ResultSet rs, int rowNum) throws SQLException {
        int version = rs.getInt("version");
        Integer versionOrNull = rs.wasNull() ? null : version;
        long lastAccess = rs.getLong("last_access_at");
        Long lastAccessOrNull = rs.wasNull() ? null : lastAccess;
        double importance = rs.getDouble("importance");
        double importanceOrDefault =
                rs.wasNull() ? L1AtomicMemory.DEFAULT_IMPORTANCE : importance;
        return new UserMemoryRow(
                rs.getString("memory_id"),
                rs.getString("category"),
                rs.getString("content"),
                rs.getLong("ts"),
                versionOrNull,
                importanceOrDefault,
                rs.getInt("access_count"),
                lastAccessOrNull,
                rs.getString("layer"));
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
