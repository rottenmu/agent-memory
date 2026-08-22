package com.zimo.module.agentmemory.storage;

import com.zimo.module.agentmemory.model.L0RawLog;
import com.zimo.module.agentmemory.model.L1AtomicMemory;
import com.zimo.module.agentmemory.model.L2SceneBlock;
import com.zimo.module.agentmemory.model.L3Persona;
import java.util.List;

/**
 * OLTP 运行时记忆仓储：承担 Agent 运行时记忆 CRUD 与逐层钻取召回链路。
 *
 * <p>仅访问 H2 MVStore 主库，禁止在此接口中承载 OLAP 分析类查询。</p>
 */
public interface OltpMemoryRepository {

    /* ---------- L0 原始日志 ---------- */

    /** 写入一条 L0 原始日志。 */
    void saveRawLog(L0RawLog log);

    /** 按 traceId 查询原始日志（溯源定位，升序）。 */
    List<L0RawLog> listRawLogsByTrace(String traceId);

    /** 按会话查询原始日志（供 ETL 增量同步）。 */
    List<L0RawLog> listRawLogsBySession(String sessionId);

    /**
     * 分页拉取全部原始日志（供 ETL 全量/增量同步，时间升序）。
     *
     * @param offset 起始行
     * @param limit  返回条数
     */
    List<L0RawLog> listRawLogsPage(int offset, int limit);

    /** 按自增 id 增量拉取原始日志（ETL 游标用，id 升序）。 */
    List<L0RawLog> listRawLogsSince(long afterId, int limit);

    /* ---------- L1 原子记忆 ---------- */

    /** 保存一条 L1 原子记忆。 */
    void saveAtomicMemory(L1AtomicMemory memory);

    /** 删除一条 L1 原子记忆。 */
    void deleteAtomicMemory(String id);

    /** 按类型召回原子记忆（用户维度，按时间降序）。 */
    List<L1AtomicMemory> recallAtomicByType(String userId, String memoryType, int limit);

    /** 按会话召回原子记忆。 */
    List<L1AtomicMemory> recallAtomicBySession(String sessionId, int limit);

    /** 按 traceId 查询原子记忆（溯源）。 */
    List<L1AtomicMemory> listAtomicByTrace(String traceId);

    /* ---------- L2 场景块 ---------- */

    /** 保存（或更新）场景块。 */
    void saveSceneBlock(L2SceneBlock block);

    /** 按会话召回最近场景块。 */
    L2SceneBlock recallSceneBySession(String sessionId);

    /** 按会话查询全部场景块（时间降序）。 */
    List<L2SceneBlock> listScenesBySession(String sessionId, int limit);

    /* ---------- L3 画像 ---------- */

    /** 保存用户画像（叠加 Caffeine 进程缓存，会话启动优先加载）。 */
    void savePersona(L3Persona persona);

    /** 按用户 + 类型读取画像（优先 Caffeine 缓存）。 */
    L3Persona getPersona(String userId, String personaType);

    /** 按用户 + 类型删除画像（并失效进程缓存）。 */
    void deletePersona(String userId, String personaType);

    /** 按用户读取全部画像。 */
    List<L3Persona> listPersonas(String userId);

    /* ---------- 钻取召回链路（L3 → L2 → L1 → L0） ---------- */

    /**
     * 溯源钻取：从画像/场景/原子记忆出发，沿 traceId 反向定位到 L0 原始日志。
     *
     * @param traceId 交互溯源 ID
     * @return 该 traceId 的原始日志链（升序）
     */
    List<L0RawLog> drillDownToRawLog(String traceId);

    /* ---------- L0 统计（页面统计卡用） ---------- */

    /** L0 消息总数（累计对话）。 */
    long countL0Total();

    /** L0 今日消息数（今日对话）。 */
    long countL0Today();
}