package com.zimo.module.agentmemory.storage;

import com.zimo.module.agentmemory.engine.MemorySpanRecord;
import com.zimo.module.agentmemory.model.L0RawLog;
import com.zimo.module.agentmemory.model.L1AtomicMemory;
import com.zimo.module.agentmemory.model.L2SceneBlock;
import com.zimo.module.agentmemory.model.L3Persona;
import com.zimo.module.agentmemory.model.UserMemoryRow;
import java.util.List;

/**
 * OLTP 运行时记忆仓储：承担 Agent 运行时记忆 CRUD 与逐层钻取召回链路。
 *
 * <p>仅访问 H2 MVStore 主库，禁止在此接口中承载 OLAP 分析类查询。</p>
 *
 * <p><b>隔离契约（强制）</b>：所有涉及用户/会话数据的查询与删除都必须同时带上
 * {@code tenantId} 与 {@code userId}（或 {@code sessionId}）。接口刻意不提供
 * 「只有 userId」的重载 —— 历史上正因为缺少租户维度，同一 {@code userId} 在不同租户
 * 间共用同一份记忆（缺陷 D8b）。把租户做成必填参数，可让漏传在编译期暴露，
 * 而不是等到线上出现跨租户串数据。</p>
 *
 * <p><b>删除契约</b>：所有删除方法返回真实影响行数（{@code int}），禁止返回
 * {@code void} 让调用方无条件认为成功（缺陷 D13）。</p>
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

    /**
     * 按租户删除指定 id 的原子记忆。
     *
     * @return 真实影响行数（0 表示不存在或不属于该租户）
     */
    int deleteAtomicMemory(String tenantId, String id);

    /**
     * 按租户 + 归属 + 类型召回原子记忆（时间降序，同分按 id 升序保证翻页稳定）。
     *
     * @param owner      L1 的 {@code user_id} 列取值：用户记忆传 userId，全局记忆传 tenantId
     * @param memoryType 记忆类型（不可为空，空值会抛异常；「不限类型取用户记忆」请用
     *                   {@link #recallAtomicByUser}）
     */
    List<L1AtomicMemory> recallAtomicByType(
            String tenantId, String owner, String memoryType, int limit, int offset);

    /** 统计该租户该归属该类型的条数（与 {@link #recallAtomicByType} 同口径）。 */
    long countAtomicByType(String tenantId, String owner, String memoryType);

    /** 按租户 + 用户召回「用户长期记忆」分页（排除 global / session_var，时间降序）。 */
    List<L1AtomicMemory> recallAtomicByUser(String tenantId, String userId, int limit, int offset);

    /** 统计该租户该用户的「用户长期记忆」条数（与 {@link #recallAtomicByUser} 同口径）。 */
    long countAtomicByUser(String tenantId, String userId);

    /** 按租户 + 会话召回会话变量分页（**仅 {@code session_var} 类型**，时间降序）。 */
    List<L1AtomicMemory> recallSessionVars(String tenantId, String sessionId, int limit, int offset);

    /** 统计该租户该会话的会话变量条数（与 {@link #recallSessionVars} 同口径）。 */
    long countSessionVars(String tenantId, String sessionId);

    /** 按 traceId 查询原子记忆（溯源，不带租户条件：traceId 全局唯一）。 */
    List<L1AtomicMemory> listAtomicByTrace(String traceId);

    /**
     * 按租户 + 归属 + 类型删除原子记忆。
     *
     * @param owner L1 的 {@code user_id} 列取值：用户记忆传 userId，全局记忆传 tenantId
     * @return 真实影响行数
     */
    int deleteAtomicByType(String tenantId, String owner, String memoryType);

    /** 删除该租户该用户的全部「用户长期记忆」（排除 global / session_var）。 */
    int deleteAtomicByUser(String tenantId, String userId);

    /** 删除该租户该会话的全部原子记忆。 */
    int deleteAtomicBySession(String tenantId, String sessionId);

    /* ---------- L1 治理支撑（合并 / 遗忘淘汰） ---------- */

    /**
     * 淘汰候选枚举：该租户的「用户长期记忆」中最近访问时间早于阈值的条目（最早优先）。
     *
     * <p>「最近访问时间」按 {@code last_access_at} 缺失时回落 {@code ts}。刻意排除
     * {@code global}（租户级配置）与 {@code session_var}（有独立的终态清理路径）。</p>
     *
     * @param touchedBefore 时间阈值（毫秒）
     */
    List<L1AtomicMemory> recallEvictionCandidates(
            String tenantId, long touchedBefore, int limit, int offset);

    /**
     * 更新原子记忆的治理字段（重要性 / 访问计数 / 最近访问时间）。
     *
     * <p>专供合并与召回计数使用：这些动作只应改动治理字段，**不得改写 {@code content} 与
     * {@code ts}** —— 记忆内容的原文形态是溯源的前提。</p>
     *
     * @param lastAccessAt 新的最近访问时间；{@code null} 表示保持原值
     * @return 真实影响行数
     */
    int updateAtomicGovernance(
            String tenantId, String id, double importance, int accessCount, Long lastAccessAt);

    /**
     * 按 id 集合批量删除（仅限该租户）。
     *
     * @return 真实影响行数
     */
    int deleteAtomicByIds(String tenantId, List<String> ids);

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

    /** 按租户 + 用户 + 类型读取画像（优先 Caffeine 缓存）。 */
    L3Persona getPersona(String tenantId, String userId, String personaType);

    /**
     * 按租户 + 用户 + 类型删除画像（并失效进程缓存）。
     *
     * @return 真实影响行数
     */
    int deletePersona(String tenantId, String userId, String personaType);

    /**
     * 按租户 + 画像 id 删除。存在的意义：列表接口返回的 id 可能来自 L3 也可能来自 L1，
     * 调用方按 id 删除时必须两表都尝试（缺陷 D12：原先只删 L1，画像纹丝不动却报成功）。
     *
     * @return 真实影响行数
     */
    int deletePersonaById(String tenantId, String id);

    /** 删除该租户该用户的全部画像。 */
    int deletePersonasByUser(String tenantId, String userId);

    /* ---------- 用户长期记忆合并分页（L3 ∪ L1） ---------- */

    /**
     * 用户长期记忆分页：L3 画像与 L1 原子记忆在 SQL 层 {@code UNION ALL} 后统一排序分页。
     *
     * <p>刻意不在内存里合并：内存合并必须先给每张表定硬上限，超限即静默丢弃（缺陷 D10）。</p>
     *
     * @param tenantId 租户标识
     * @param userId   用户标识
     * @param category 业务类别过滤（为空表示不限）
     * @param limit    返回条数上限
     * @param offset   起始偏移
     * @return 统一行视图（时间降序，同分按 id 升序保证顺序稳定）
     */
    List<UserMemoryRow> pageUserMemory(String tenantId, String userId, String category, int limit, int offset);

    /** 统计用户长期记忆总数（与 {@link #pageUserMemory} 同口径，供 total 字段使用）。 */
    long countUserMemory(String tenantId, String userId, String category);

    /**
     * 按 id 集合查询用户长期记忆（L3 ∪ L1），用于批量读取。
     *
     * <p>刻意不做「扫全量再按 id 过滤」：扫描有上限，超限即静默漏查（与缺陷 D10 同源）。
     * id 条件下推到 SQL，不设隐式截断。</p>
     *
     * @param tenantId 租户标识（隔离维度，必填）
     * @param userId   用户标识（限定单个用户范围，避免越权读他人记忆）
     * @param ids      记忆主键集合（调用方需先去重并限制条数）
     * @return 命中行（时间降序，同分按 id 升序）；未命中的 id 不出现在结果中
     */
    List<UserMemoryRow> listUserMemoryByIds(String tenantId, String userId, List<String> ids);

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

    /* ---------- 分层规模统计（/metrics 用） ---------- */

    /**
     * 该租户的 L1「用户长期记忆」条数（排除全局记忆与会话变量）。
     *
     * <p>指标接口同样属于跨租户可访问面，因此统计必须带租户条件 ——
     * 否则可以从条数变化推断其他租户的记忆规模。</p>
     *
     * @param tenantId 租户标识
     * @return 条数；无数据返回 0
     */
    int countAtomicByTenant(String tenantId);

    /**
     * 该租户的 L3 画像条数。
     *
     * @param tenantId 租户标识
     * @return 条数；无数据返回 0
     */
    int countPersonaByTenant(String tenantId);

    /* ---------- 记忆审计（memory_span，只增不改） ---------- */

    /**
     * 追加一条记忆审计记录。
     *
     * <p>本表是 {@code append-only} 的观测事实源：同步阶段写骨架（{@code phase=sync}），
     * 异步回写在链路收尾后写结果（{@code phase=async}）。<b>刻意不提供 update / delete</b>
     * —— 审计可被改写就不再是审计；「异步是否完成」由是否存在对应的 async 记录表达，
     * 而不是靠把 sync 记录改状态。</p>
     *
     * @param record 审计记录（id 由数据库生成）
     */
    void saveMemorySpan(MemorySpanRecord record);

    /**
     * 按链路查询审计记录（时间升序，同毫秒按自增 id 升序）。
     *
     * @param traceId 链路 ID；为空返回空列表（不返回全表：那会让一次参数漏传变成全表导出）
     */
    List<MemorySpanRecord> listMemorySpansByTrace(String traceId);
}
