package com.zimo.module.agentmemory.analytics;

import com.zimo.module.agentmemory.storage.OlapAnalyticsRepository;
import java.util.List;
import java.util.Map;

/**
 * 记忆分析服务：OLAP 分析层门面，提供会话统计、时序分析与记忆洞察报表 API。
 *
 * <p>本服务只调用 {@link OlapAnalyticsRepository}（OLAP 副库），不访问运行时
 * OLTP 数据，保证读写负载严格分离。</p>
 */
public class MemoryAnalyticsService {

    private final OlapAnalyticsRepository olap;

    public MemoryAnalyticsService(OlapAnalyticsRepository olap) {
        this.olap = olap;
    }

    /** 会话聚合统计（消息数/token 消耗/活跃时长）。 */
    public List<Map<String, Object>> sessionStats() {
        return olap.sessionStats();
    }

    /** 用户行为时序分析（近 {@code days} 天消息量）。 */
    public List<Map<String, Object>> userDailyActivity(String userId, int days) {
        return olap.userDailyActivity(userId, days);
    }

    /** 记忆蒸馏质量评估（L0 事件构成）。 */
    public List<Map<String, Object>> memoryDistillationStats() {
        return olap.memoryDistillationStats();
    }

    /** 按 traceId 查询完整事件链（溯源）。 */
    public List<Map<String, Object>> traceEvents(String traceId) {
        return olap.traceEvents(traceId);
    }

    /** 执行自定义 OLAP SQL（Calcite）。 */
    public List<Map<String, Object>> query(String sql) {
        return olap.query(sql);
    }

    /** 触发一次手动 ETL 同步（管理端调试用）。 */
    public void triggerSync(Runnable syncTask) {
        syncTask.run();
    }
}
