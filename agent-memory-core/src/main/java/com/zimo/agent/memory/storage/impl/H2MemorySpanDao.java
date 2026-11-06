package com.zimo.agent.memory.storage.impl;

import com.zimo.agent.memory.engine.MemorySpanRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 记忆审计表（{@code memory_span}）读写。
 *
 * <p>表结构遵循与四层记忆表相同的硬约束：<b>无外键、下划线字段命名、加列用
 * {@code ADD COLUMN IF NOT EXISTS}</b>。库是已存在的 H2 MVStore 文件库，建表必须幂等且
 * <b>不得重建表文件</b>。</p>
 *
 * <p><b>为什么单独一个 DAO 而不是塞进 {@code H2OltpMemoryRepository}</b>：审计表与四层记忆
 * 表是<b>两类数据</b> —— 前者是观测/运维事实（只增不改、按 traceId 查），后者是业务记忆
 * （按租户用户查、参与治理）。混在一起会让仓储类的行数随观测字段增长而膨胀。</p>
 *
 * <p><b>索引</b>：{@code trace_id} 建索引。面板按 traceId 反查是本表最主要的读路径，
 * 无索引时会退化为全表扫描，而本表是<b>只增不减</b>的（每一轮对话都会写），
 * 全表扫描的成本随时间线性上升。</p>
 */
final class H2MemorySpanDao {

    /** 建表（幂等）。 */
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS memory_span (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              trace_id VARCHAR(64),
              session_id VARCHAR(64),
              user_id VARCHAR(64),
              tenant_id VARCHAR(64),
              phase VARCHAR(16),
              operation VARCHAR(16),
              layer VARCHAR(16),
              memory_ids CLOB,
              importance DOUBLE,
              recall_score DOUBLE,
              recall_count INT,
              truncated BOOLEAN,
              latency_ms BIGINT,
              status VARCHAR(16),
              ts BIGINT NOT NULL
            )""";

    private static final String INSERT = """
            INSERT INTO memory_span (trace_id, session_id, user_id, tenant_id, phase, operation,
              layer, memory_ids, importance, recall_score, recall_count, truncated, latency_ms,
              status, ts)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String SELECT_COLUMNS =
            "SELECT id, trace_id, session_id, user_id, tenant_id, phase, operation, layer,"
                    + " memory_ids, importance, recall_score, recall_count, truncated, latency_ms,"
                    + " status, ts FROM memory_span";

    private final JdbcTemplate jdbc;

    /**
     * @param jdbc 共享的 JDBC 模板（由仓储统一提供，保证同一数据源）
     */
    H2MemorySpanDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute(CREATE_TABLE);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_memory_span_trace ON memory_span(trace_id)");
    }

    /** 追加一条审计记录（append-only，不提供更新与删除）。 */
    void save(MemorySpanRecord record) {
        jdbc.update(INSERT,
                record.traceId(), record.sessionId(), record.userId(), record.tenantId(),
                record.phase(), record.operation(), record.layer(), record.memoryIds(),
                record.importance(), record.recallScore(), record.recallCount(),
                record.truncated(), record.latencyMs(), record.status(), record.ts());
    }

    /**
     * 按链路查询审计记录（时间升序，同毫秒按自增 id 升序保证顺序稳定）。
     *
     * <p>末位按 id 兜底是硬要求：一轮对话的同步骨架与异步结果可能落在同一毫秒，
     * 无兜底排序时两条记录的先后会漂移，「先 sync 后 async」这条断言就会偶发失败。</p>
     */
    List<MemorySpanRecord> listByTrace(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return List.of();
        }
        return jdbc.query(SELECT_COLUMNS + " WHERE trace_id = ? ORDER BY ts ASC, id ASC",
                this::map, traceId);
    }

    private MemorySpanRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new MemorySpanRecord(
                rs.getLong("id"),
                rs.getString("trace_id"),
                rs.getString("session_id"),
                rs.getString("user_id"),
                rs.getString("tenant_id"),
                rs.getString("phase"),
                rs.getString("operation"),
                rs.getString("layer"),
                rs.getString("memory_ids"),
                nullableDouble(rs, "importance"),
                nullableDouble(rs, "recall_score"),
                nullableInt(rs, "recall_count"),
                nullableBoolean(rs, "truncated"),
                nullableLong(rs, "latency_ms"),
                rs.getString("status"),
                rs.getLong("ts"));
    }

    /* 可空列必须显式判 NULL：直接 getDouble 会把 NULL 静默变成 0.0，
       于是「没测到分数」与「分数是 0」在审计里再也分不出来。 */

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }
}
