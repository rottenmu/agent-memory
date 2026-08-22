package com.zimo.module.agentmemory.analytics.impl;

import com.zimo.module.agentmemory.storage.OlapAnalyticsRepository;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowFileReader;
import org.apache.arrow.vector.ipc.ArrowFileWriter;
import org.apache.arrow.vector.ipc.message.ArrowBlock;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.calcite.adapter.java.AbstractQueryableTable;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Arrow + Calcite 实现的 OLAP 分析仓储（纯 Java，无 JNI）。
 *
 * <p>数据模型为 L0 日志宽表副本（列式内存 + Arrow IPC 文件持久化）；查询层
 * 用 Apache Calcite 将 SQL 下推到内存表（{@code ScannableTable}），运行时记忆
 * 查询不经过本仓储，仅后台异步分析使用。DuckDB 方案（JNI）预留开关位。</p>
 */
public class ArrowOlapAnalyticsRepository implements OlapAnalyticsRepository {

    private static final Logger log = LoggerFactory.getLogger(ArrowOlapAnalyticsRepository.class);

    /** 宽表列定义（与 H2 L0 对齐，下划线命名）。 */
    private static final String[] COLUMNS = {
            "trace_id", "session_id", "user_id", "ts", "role", "content", "tokens"
    };

    private final Path dataFile;
    private final Path cursorFile;
    private final RootAllocator allocator;
    /** 内存列式行数据（每行 Object[]，按 COLUMNS 顺序）。 */
    private volatile List<Object[]> rows = new ArrayList<>();
    /** ETL 同步游标：已同步的 H2 L0 最大自增 id；{@code -1} 表示未初始化（需全量重建）。 */
    private volatile long syncCursor = -1L;

    public ArrowOlapAnalyticsRepository(Path arrowDataFile) {
        this.dataFile = arrowDataFile;
        this.cursorFile = arrowDataFile.resolveSibling(arrowDataFile.getFileName().toString() + ".cursor");
        this.allocator = new RootAllocator(Long.MAX_VALUE);
        loadFromFile();
        loadCursor();
    }

    /* ================= 数据装载 ================= */

    /** 全量重建数据集（由 ETL 任务调用）；先关闭旧向量再重建，避免泄漏。 */
    @Override
    public synchronized void refresh() {
        loadFromFile();
    }

    /** 用新行集替换内存数据并导出 Arrow IPC 文件。 */
    @Override
    public synchronized void replaceRows(List<Object[]> newRows) {
        this.rows = newRows == null ? new ArrayList<>() : newRows;
        exportToFile();
        log.info("OLAP 数据集已刷新：{} 行，文件 {}", rows.size(), dataFile);
    }

    /** 增量追加行并导出落盘（ETL 游标同步用）。 */
    @Override
    public synchronized void appendRows(List<Object[]> newRows) {
        if (newRows == null || newRows.isEmpty()) {
            return;
        }
        List<Object[]> merged = new ArrayList<>(this.rows);
        merged.addAll(newRows);
        this.rows = merged;
        exportToFile();
        log.info("OLAP 数据集已增量追加：{} 行，总计 {}", newRows.size(), rows.size());
    }

    @Override
    public long syncCursor() {
        return syncCursor;
    }

    @Override
    public synchronized void updateSyncCursor(long lastSyncedId) {
        this.syncCursor = lastSyncedId;
        try {
            if (cursorFile.getParent() != null) {
                Files.createDirectories(cursorFile.getParent());
            }
            Files.writeString(cursorFile, String.valueOf(lastSyncedId),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("同步游标持久化失败：{}", e.getMessage());
        }
    }

    private void loadCursor() {
        try {
            if (Files.exists(cursorFile)) {
                this.syncCursor = Long.parseLong(Files.readString(cursorFile).trim());
            }
        } catch (Exception e) {
            log.warn("同步游标读取失败，置为未初始化（首轮全量重建）：{}", e.getMessage());
            this.syncCursor = -1L;
        }
    }

    private void loadFromFile() {
        if (!Files.exists(dataFile)) {
            log.info("OLAP 数据文件不存在，使用空数据集：{}", dataFile);
            rows = new ArrayList<>();
            return;
        }
        List<Object[]> loaded = new ArrayList<>();
        try (ArrowFileReader reader = new ArrowFileReader(
                FileChannel.open(dataFile, StandardOpenOption.READ), allocator)) {
            for (ArrowBlock block : reader.getRecordBlocks()) {
                reader.loadRecordBatch(block);
                VectorSchemaRoot root = reader.getVectorSchemaRoot();
                int count = root.getRowCount();
                VarCharVector trace = (VarCharVector) root.getVector(0);
                VarCharVector session = (VarCharVector) root.getVector(1);
                VarCharVector user = (VarCharVector) root.getVector(2);
                BigIntVector ts = (BigIntVector) root.getVector(3);
                VarCharVector role = (VarCharVector) root.getVector(4);
                VarCharVector content = (VarCharVector) root.getVector(5);
                BigIntVector tokens = (BigIntVector) root.getVector(6);
                for (int index = 0; index < count; index++) {
                    loaded.add(new Object[]{
                            toString(trace, index),
                            toString(session, index),
                            toString(user, index),
                            ts.isNull(index) ? null : ts.get(index),
                            toString(role, index),
                            toString(content, index),
                            tokens.isNull(index) ? null : tokens.get(index)
                    });
                }
                root.clear();
            }
            rows = loaded;
            log.info("OLAP 数据集已从 {} 加载 {} 行", dataFile, loaded.size());
        } catch (Exception e) {
            log.warn("OLAP 数据文件加载失败，使用空数据集：{}", e.getMessage());
            rows = new ArrayList<>();
        }
    }

    private void exportToFile() {
        try {
            Path parent = dataFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (VectorSchemaRoot root = buildRoot(rows);
                 ArrowFileWriter writer = new ArrowFileWriter(root, null,
                         FileChannel.open(dataFile,
                                 StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) {
                writer.start();
                writer.writeBatch();
                writer.end();
            }
        } catch (Exception e) {
            log.warn("OLAP 数据导出失败：{}", e.getMessage());
        }
    }

    private VectorSchemaRoot buildRoot(List<Object[]> data) {
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("trace_id", FieldType.nullable(new ArrowType.Utf8()), null));
        fields.add(new Field("session_id", FieldType.nullable(new ArrowType.Utf8()), null));
        fields.add(new Field("user_id", FieldType.nullable(new ArrowType.Utf8()), null));
        fields.add(new Field("ts", FieldType.nullable(new ArrowType.Int(64, true)), null));
        fields.add(new Field("role", FieldType.nullable(new ArrowType.Utf8()), null));
        fields.add(new Field("content", FieldType.nullable(new ArrowType.Utf8()), null));
        fields.add(new Field("tokens", FieldType.nullable(new ArrowType.Int(64, true)), null));

        VectorSchemaRoot root = VectorSchemaRoot.create(new Schema(fields), allocator);
        root.allocateNew();
        int count = data.size();
        for (int index = 0; index < count; index++) {
            Object[] row = data.get(index);
            ((VarCharVector) root.getVector(0)).setSafe(index, str(row[0]));
            ((VarCharVector) root.getVector(1)).setSafe(index, str(row[1]));
            ((VarCharVector) root.getVector(2)).setSafe(index, str(row[2]));
            ((BigIntVector) root.getVector(3)).setSafe(index, row[3] == null ? 0L : ((Number) row[3]).longValue());
            ((VarCharVector) root.getVector(4)).setSafe(index, str(row[4]));
            ((VarCharVector) root.getVector(5)).setSafe(index, str(row[5]));
            ((BigIntVector) root.getVector(6)).setSafe(index, row[6] == null ? 0L : ((Number) row[6]).longValue());
        }
        root.setRowCount(count);
        return root;
    }

    /* ================= 分析查询 ================= */

    @Override
    public List<Map<String, Object>> sessionStats() {
        Map<String, long[]> agg = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String sessionId = text(row[1]);
            long[] stats = agg.computeIfAbsent(sessionId, ignored -> new long[4]); // msgs, tokens, minTs, maxTs
            stats[0]++;
            if (row[6] != null) {
                stats[1] += ((Number) row[6]).longValue();
            }
            long ts = row[3] == null ? 0L : ((Number) row[3]).longValue();
            if (stats[2] == 0 || ts < stats[2]) {
                stats[2] = ts;
            }
            if (ts > stats[3]) {
                stats[3] = ts;
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        agg.forEach((sessionId, stats) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("session_id", sessionId);
            item.put("message_count", stats[0]);
            item.put("total_tokens", stats[1]);
            item.put("active_duration_ms", stats[3] - stats[2]);
            result.add(item);
        });
        return result;
    }

    @Override
    public List<Map<String, Object>> userDailyActivity(String userId, int days) {
        Map<Long, long[]> daily = new LinkedHashMap<>();
        for (Object[] row : rows) {
            if (userId != null && !userId.isEmpty() && !userId.equals(text(row[2]))) {
                continue;
            }
            long ts = row[3] == null ? 0L : ((Number) row[3]).longValue();
            long dayBucket = ts / 86_400_000L;
            daily.computeIfAbsent(dayBucket, ignored -> new long[2])[0]++;
        }
        long now = System.currentTimeMillis() / 86_400_000L;
        List<Map<String, Object>> result = new ArrayList<>();
        for (long day = now - Math.max(1, days) + 1; day <= now; day++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("day", day * 86_400_000L);
            item.put("message_count", daily.getOrDefault(day, new long[2])[0]);
            result.add(item);
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> memoryDistillationStats() {
        // OLAP 侧仅统计 L0 事件构成（role 分布），L1 蒸馏质量由 H2 侧查询
        Map<String, Long> roleCount = rows.stream()
                .collect(Collectors.groupingBy(row -> text(row[4]), Collectors.counting()));
        List<Map<String, Object>> result = new ArrayList<>();
        roleCount.forEach((role, count) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", role);
            item.put("event_count", count);
            result.add(item);
        });
        return result;
    }

    @Override
    public List<Map<String, Object>> traceEvents(String traceId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            if (traceId.equals(text(row[0]))) {
                result.add(rowToMap(row));
            }
        }
        return result;
    }

    /** Calcite SQL 查询（内存宽表 olap_l0_log）。 */
    @Override
    public List<Map<String, Object>> query(String sql) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:calcite:")) {
            CalciteConnection calcite = connection.unwrap(CalciteConnection.class);
            SchemaPlus rootSchema = calcite.getRootSchema();
            if (rootSchema.getTable("olap_l0_log") == null) {
                // Calcite 默认将未加引号的标识符大写化，同时注册大小写两个表名
                rootSchema.add("olap_l0_log", new L0LogTable());
                rootSchema.add("OLAP_L0_LOG", new L0LogTable());
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int columns = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int index = 1; index <= columns; index++) {
                        // 列标签大写→转小写，保持与 COLUMNS 小写命名一致
                        row.put(meta.getColumnLabel(index).toLowerCase(), rs.getObject(index));
                    }
                    result.add(row);
                }
            }
        } catch (Exception e) {
            log.warn("OLAP SQL 查询失败：{}", e.getMessage());
        }
        return result;
    }

    /** Calcite 内存表：将当前行集暴露为可扫描表。 */
    private class L0LogTable extends AbstractTable implements org.apache.calcite.schema.ScannableTable {

        @Override
        public RelDataType getRowType(RelDataTypeFactory typeFactory) {
            List<RelDataType> types = List.of(
                    typeFactory.createJavaType(String.class),
                    typeFactory.createJavaType(String.class),
                    typeFactory.createJavaType(String.class),
                    typeFactory.createJavaType(Long.class),
                    typeFactory.createJavaType(String.class),
                    typeFactory.createJavaType(String.class),
                    typeFactory.createJavaType(Long.class));
            // 列名用大写：Calcite 将未加引号的标识符大写化后按大小写匹配列
            List<String> upperColumns = new ArrayList<>();
            for (String column : COLUMNS) {
                upperColumns.add(column.toUpperCase());
            }
            return typeFactory.createStructType(Pair.zip(upperColumns, types));
        }

        @Override
        public Enumerable<Object[]> scan(org.apache.calcite.DataContext root) {
            return Linq4j.asEnumerable(rows);
        }
    }

    /* ================= 工具 ================= */

    private Map<String, Object> rowToMap(Object[] row) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < COLUMNS.length; index++) {
            map.put(COLUMNS[index], row[index]);
        }
        return map;
    }

    private static String toString(VarCharVector vector, int index) {
        if (vector.isNull(index)) {
            return null;
        }
        // 必须显式 UTF-8 解码（Arrow 文件为 UTF-8 字节，Windows 默认 GBK 会乱码）
        return new String(vector.get(index), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] str(Object value) {
        return value == null ? new byte[0] : String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 当前内存行数（供 ETL 游标判断）。 */
    public int rowCount() {
        return rows.size();
    }
}
