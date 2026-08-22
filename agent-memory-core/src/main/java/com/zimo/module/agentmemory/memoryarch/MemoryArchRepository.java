package com.zimo.module.agentmemory.memoryarch;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;

/**
 * 记忆分层架构仓储（H2 JdbcTemplate，与 agent-memory OLTP 共享 DataSource）。
 */
public class MemoryArchRepository {

    private final JdbcTemplate jdbc;

    public MemoryArchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        initSchema();
    }

    /** 建表（幂等）。 */
    private void initSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS memory_arch_config (
                  id VARCHAR(32) PRIMARY KEY,
                  type VARCHAR(16) NOT NULL,
                  name VARCHAR(128) NOT NULL,
                  summary CLOB,
                  background CLOB,
                  content CLOB,
                  source VARCHAR(8) NOT NULL DEFAULT '手动',
                  version INT NOT NULL DEFAULT 1,
                  updated_ts BIGINT NOT NULL
                )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_arch_type_ts ON memory_arch_config(type, updated_ts DESC)");
    }

    public int countByType(String type) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM memory_arch_config WHERE type = ?", Integer.class, type);
        return c == null ? 0 : c;
    }

    public int countAll() {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM memory_arch_config", Integer.class);
        return c == null ? 0 : c;
    }

    /** 统计来源为"自动"的 USER 档案数（已提取）。 */
    public int countExtracted() {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM memory_arch_config WHERE type = 'USER' AND source = '自动'", Integer.class);
        return c == null ? 0 : c;
    }

    /** 分页查询（type 可空、q 关键词匹配 name/summary/background）。 */
    public List<MemoryArchConfig> list(String type, String q, int page, int size) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, type, name, summary, background, content, source, version, updated_ts "
                        + "FROM memory_arch_config WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (type != null && !type.isBlank()) {
            sql.append(" AND type = ?");
            args.add(type);
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (name LIKE ? OR summary LIKE ? OR background LIKE ?)");
            String like = "%" + q + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY updated_ts DESC LIMIT ? OFFSET ?");
        args.add(Math.max(1, Math.min(size, 200)));
        args.add(Math.max(0, page) * Math.max(1, size));
        return jdbc.query(sql.toString(), this::map, args.toArray());
    }

    public int countList(String type, String q) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM memory_arch_config WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (type != null && !type.isBlank()) {
            sql.append(" AND type = ?");
            args.add(type);
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (name LIKE ? OR summary LIKE ? OR background LIKE ?)");
            String like = "%" + q + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Integer c = jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
        return c == null ? 0 : c;
    }

    public MemoryArchConfig findById(String id) {
        java.util.List<MemoryArchConfig> list = jdbc.query(
                "SELECT id, type, name, summary, background, content, source, version, updated_ts "
                        + "FROM memory_arch_config WHERE id = ?",
                this::map, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public void insert(MemoryArchConfig c) {
        jdbc.update("""
                        INSERT INTO memory_arch_config
                          (id, type, name, summary, background, content, source, version, updated_ts)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                c.id(), c.type(), c.name(), c.summary(), c.background(), c.content(),
                c.source(), c.version(), c.updatedTs());
    }

    public int update(MemoryArchConfig c) {
        return jdbc.update("""
                        UPDATE memory_arch_config
                           SET summary = ?, background = ?, content = ?, source = ?,
                               version = version + 1, updated_ts = ?
                         WHERE id = ?""",
                c.summary(), c.background(), c.content(), c.source(), c.updatedTs(), c.id());
    }

    public int updateSource(String id, String source) {
        return jdbc.update(
                "UPDATE memory_arch_config SET source = ?, version = version + 1, updated_ts = ? WHERE id = ?",
                source, System.currentTimeMillis(), id);
    }

    public int delete(String id) {
        return jdbc.update("DELETE FROM memory_arch_config WHERE id = ?", id);
    }

    private MemoryArchConfig map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new MemoryArchConfig(
                rs.getString("id"),
                rs.getString("type"),
                rs.getString("name"),
                rs.getString("summary"),
                rs.getString("background"),
                rs.getString("content"),
                rs.getString("source"),
                rs.getInt("version"),
                rs.getLong("updated_ts"));
    }
}
