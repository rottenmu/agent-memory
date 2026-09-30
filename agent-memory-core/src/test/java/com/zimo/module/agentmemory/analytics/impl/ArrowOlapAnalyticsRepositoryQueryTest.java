package com.zimo.module.agentmemory.analytics.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowFileWriter;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ArrowOlapAnalyticsRepository Calcite SQL 查询能力测试（复现聚合/过滤问题）。
 */
class ArrowOlapAnalyticsRepositoryQueryTest {

    @TempDir
    Path tempDir;

    private ArrowOlapAnalyticsRepository newRepo() {
        return new ArrowOlapAnalyticsRepository(tempDir.resolve("l0_log.arrow"));
    }

    private void seed(ArrowOlapAnalyticsRepository repo) {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"t1", "s1", "u1", 1000L, "user", "你好", 10L});
        rows.add(new Object[]{"t2", "s1", "u1", 2000L, "assistant", "你好，有什么可以帮你", 20L});
        rows.add(new Object[]{"t3", "s2", "u2", 3000L, "user", "帮我查库存", 15L});
        repo.replaceRows(rows);
    }

    @Test
    void selectStarReturnsAllRows() {
        ArrowOlapAnalyticsRepository repo = newRepo();
        seed(repo);
        List<Map<String, Object>> result = repo.query("SELECT * FROM olap_l0_log");
        assertThat(result).hasSize(3);
    }

    @Test
    void whereFilterReturnsMatchingRows() {
        ArrowOlapAnalyticsRepository repo = newRepo();
        seed(repo);
        List<Map<String, Object>> result = repo.query(
                "SELECT trace_id FROM olap_l0_log WHERE role = 'user'");
        assertThat(result).hasSize(2);
    }

    @Test
    void groupByReturnsAggregatedRows() {
        ArrowOlapAnalyticsRepository repo = newRepo();
        seed(repo);
        List<Map<String, Object>> result = repo.query(
                "SELECT role, COUNT(*) AS cnt FROM olap_l0_log GROUP BY role");
        assertThat(result).hasSize(2);
    }

    @Test
    void distinctReturnsUniqueValues() {
        ArrowOlapAnalyticsRepository repo = newRepo();
        seed(repo);
        List<Map<String, Object>> result = repo.query("SELECT DISTINCT session_id FROM olap_l0_log");
        assertThat(result).hasSize(2);
    }

    @Test
    void queriesAndReloadsSourceColumn() {
        ArrowOlapAnalyticsRepository repo = newRepo();
        repo.replaceRows(List.of(
                new Object[]{"t1", "s1", "u1", 1000L, "user", "你好", 10L, "user_message"},
                new Object[]{"t2", "s1", "u1", 2000L, "tool", "查询完成", 2L, "tool_result"}));

        List<Map<String, Object>> result = repo.query(
                "SELECT source, COUNT(*) AS cnt FROM olap_l0_log GROUP BY source");
        assertThat(result).hasSize(2);
        assertThat(result).extracting(row -> row.get("source"))
                .containsExactlyInAnyOrder("user_message", "tool_result");

        ArrowOlapAnalyticsRepository restarted = newRepo();
        List<Map<String, Object>> reloaded = restarted.query(
                "SELECT source FROM olap_l0_log ORDER BY trace_id");
        assertThat(reloaded).extracting(row -> row.get("source"))
                .containsExactly("user_message", "tool_result");
    }

    @Test
    void loadsLegacySevenColumnFileAndPreservesRowsWhenAppending() throws Exception {
        Path file = tempDir.resolve("legacy-l0.arrow");
        writeLegacyArrowFile(file);
        ArrowOlapAnalyticsRepository repo = new ArrowOlapAnalyticsRepository(file);

        List<Map<String, Object>> initial = repo.query(
                "SELECT trace_id, source FROM olap_l0_log");
        assertThat(initial).hasSize(1);
        assertThat(initial.get(0).get("trace_id")).isEqualTo("legacy");
        assertThat(initial.get(0).get("source")).isNull();

        repo.appendRows(List.<Object[]>of(
                new Object[]{"new", "s1", "u1", 2000L, "tool", "完成", 3L, "tool_result"}));
        ArrowOlapAnalyticsRepository restarted = new ArrowOlapAnalyticsRepository(file);
        List<Map<String, Object>> reloaded = restarted.query(
                "SELECT trace_id, source FROM olap_l0_log ORDER BY trace_id");
        assertThat(reloaded).extracting(row -> row.get("trace_id"))
                .containsExactly("legacy", "new");
        assertThat(reloaded).extracting(row -> row.get("source"))
                .containsExactly(null, "tool_result");
    }

    private static void writeLegacyArrowFile(Path file) throws Exception {
        List<Field> fields = List.of(
                textField("trace_id"), textField("session_id"), textField("user_id"),
                new Field("ts", FieldType.nullable(new ArrowType.Int(64, true)), null),
                textField("role"), textField("content"),
                new Field("tokens", FieldType.nullable(new ArrowType.Int(64, true)), null));
        try (RootAllocator allocator = new RootAllocator(Long.MAX_VALUE);
             VectorSchemaRoot root = VectorSchemaRoot.create(new Schema(fields), allocator)) {
            root.allocateNew();
            setText(root, 0, "legacy");
            setText(root, 1, "s1");
            setText(root, 2, "u1");
            ((BigIntVector) root.getVector(3)).setSafe(0, 1000L);
            setText(root, 4, "user");
            setText(root, 5, "历史记录");
            ((BigIntVector) root.getVector(6)).setSafe(0, 1L);
            root.setRowCount(1);
            try (ArrowFileWriter writer = new ArrowFileWriter(root, null,
                    FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE))) {
                writer.start();
                writer.writeBatch();
                writer.end();
            }
        }
    }

    private static Field textField(String name) {
        return new Field(name, FieldType.nullable(new ArrowType.Utf8()), null);
    }

    private static void setText(VectorSchemaRoot root, int index, String value) {
        ((VarCharVector) root.getVector(index)).setSafe(0, value.getBytes(StandardCharsets.UTF_8));
    }
}
