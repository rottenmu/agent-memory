package com.zimo.module.agentmemory.storage;

import com.zimo.module.agentmemory.model.L1AtomicMemory;
import java.util.List;
import java.util.Map;

/**
 * OLAP 离线分析仓储：仅用于后台异步离线分析，不承担运行时记忆查询流量。
 *
 * <p>数据来源为 {@code AsyncLogSyncTask} 从 H2 增量同步的 L0 日志副本
 * （Arrow 内存列式数据集 / Parquet 文件）；提供会话聚合、时序、蒸馏评估等
 * 分析能力（Calcite 查询执行）。</p>
 */
public interface OlapAnalyticsRepository {

    /** 会话聚合统计：按会话统计消息数、token 消耗、活跃时长。 */
    List<Map<String, Object>> sessionStats();

    /** 用户行为时序：按天聚合用户消息量。 */
    List<Map<String, Object>> userDailyActivity(String userId, int days);

    /** 记忆蒸馏质量评估：按记忆类型统计抽取量。 */
    List<Map<String, Object>> memoryDistillationStats();

    /** 按 traceId 查询分析副本中的完整事件链。 */
    List<Map<String, Object>> traceEvents(String traceId);

    /** 执行自定义 SQL（Calcite 对 Arrow 数据集/Parquet 的查询）。 */
    List<Map<String, Object>> query(String sql);

    /** 刷新分析数据集（重新加载 Parquet/重建内存列式数据）。 */
    void refresh();

    /** 用新行集整体替换数据集（ETL 首轮全量重建用）；实现需负责落盘。 */
    void replaceRows(java.util.List<Object[]> rows);

    /** 增量追加行（ETL 游标同步用）；追加后自动导出落盘。 */
    void appendRows(java.util.List<Object[]> newRows);

    /** 当前 ETL 同步游标（已同步的 L0 最大 id）。 */
    long syncCursor();

    /** 更新 ETL 同步游标并持久化。 */
    void updateSyncCursor(long lastSyncedId);
}
