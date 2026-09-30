package com.zimo.module.agentmemory.model;

import java.util.List;

/**
 * L1 原子记忆：从原始日志中抽取的不可再分的最小记忆单元。
 *
 * <p>例如「用户偏好报表格式」「某单据已完成审批」「客户要求周报邮件」。
 * 携带 {@code traceId} 溯源到 L0；{@code embedding} 预留向量（本期不写入，
 * 召回走 S1 可解释混合打分，见 PRD §3.2）。</p>
 *
 * <p>隔离与计量字段：{@code tenantId} 与 {@code userId} 共同构成查询的**强制双条件**，
 * 缺一都会造成跨租户或跨用户串数据；{@code importance} / {@code accessCount} /
 * {@code lastAccessAt} 供召回打分与遗忘淘汰使用。</p>
 *
 * @param id           记忆 ID
 * @param traceId      溯源 ID（指向 L0 原始日志）
 * @param sessionId    会话标识
 * @param userId       用户标识（{@code global} 类型时该列承载租户 ID，见 {@code saveGlobalMemory}）
 * @param memoryType   记忆类型：preference / fact / habit / task / custom / session_var / global
 * @param content      记忆内容
 * @param embedding    内容向量（可空，本期恒为空）
 * @param ts           抽取时间戳（毫秒）
 * @param tenantId     租户标识（不可为空，空值归一为 {@link #DEFAULT_TENANT}）
 * @param importance   重要性 0~1（越界会被夹到区间内）
 * @param accessCount  被召回次数（负数归一为 0）
 * @param lastAccessAt 最近一次被召回时间（毫秒，从未被召回为 {@code null}）
 */
public record L1AtomicMemory(
        String id,
        String traceId,
        String sessionId,
        String userId,
        String memoryType,
        String content,
        float[] embedding,
        long ts,
        String tenantId,
        double importance,
        int accessCount,
        Long lastAccessAt) {

    /** 类型常量：用户偏好。 */
    public static final String TYPE_PREFERENCE = "preference";
    /** 类型常量：客观事实。 */
    public static final String TYPE_FACT = "fact";
    /** 类型常量：历史习惯。 */
    public static final String TYPE_HABIT = "habit";
    /** 类型常量：任务进展。 */
    public static final String TYPE_TASK = "task";
    /** 类型常量：自定义。 */
    public static final String TYPE_CUSTOM = "custom";
    /** 类型常量：会话变量（L1 承载的短期会话记忆，按 sessionId 召回）。 */
    public static final String TYPE_SESSION_VAR = "session_var";
    /**
     * 类型常量：全局记忆（跨会话、跨智能体共享）。
     *
     * <p>注意语义重载：该类型记录的 {@code user_id} 列**存的是 tenantId**，
     * 因此「按 user_id 取全部类型」时若不排除本类型，会把租户级数据混进用户记忆。</p>
     */
    public static final String TYPE_GLOBAL = "global";

    /** 「用户长期记忆」的类型白名单之外的类型：这些不属任何单个用户。 */
    public static final java.util.List<String> NON_USER_TYPES =
            List.of(TYPE_GLOBAL, TYPE_SESSION_VAR);

    /** 缺省租户：既有数据与未显式指定租户的调用统一落这里。 */
    public static final String DEFAULT_TENANT = "default";
    /** 默认重要性（未经重要性评估时的中性取值）。 */
    public static final double DEFAULT_IMPORTANCE = 0.5;

    /**
     * 归一化非法取值，让「越界的重要性」「负的召回次数」「空租户」在构造期即被修正，
     * 而不是等到打分或查询时表现为难以解释的结果。
     */
    public L1AtomicMemory {
        tenantId = tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT : tenantId;
        accessCount = Math.max(0, accessCount);
        importance = Double.isNaN(importance) || importance < 0.0 ? 0.0
                : Math.min(importance, 1.0);
    }

    /**
     * 新建一条原子记忆：{@code embedding} / {@code accessCount} / {@code lastAccessAt}
     * 取默认值，{@code importance} 用中性默认。
     *
     * @param id         记忆 ID
     * @param traceId    溯源 ID
     * @param sessionId  会话标识
     * @param userId     用户标识
     * @param memoryType 记忆类型
     * @param content    记忆内容
     * @param ts         时间戳（毫秒）
     * @param tenantId   租户标识
     * @return 新记忆实例
     */
    public static L1AtomicMemory create(
            String id, String traceId, String sessionId, String userId,
            String memoryType, String content, long ts, String tenantId) {
        return create(id, traceId, sessionId, userId, memoryType, content, ts, tenantId,
                DEFAULT_IMPORTANCE);
    }

    /**
     * 新建一条原子记忆并指定重要性。
     *
     * <p>重要性由 {@code ImportanceScorer} 评估或调用方显式给出，因此写入路径需要能把它带进来；
     * {@code embedding} / {@code accessCount} / {@code lastAccessAt} 仍取默认值。</p>
     *
     * @param id         记忆 ID
     * @param traceId    溯源 ID
     * @param sessionId  会话标识
     * @param userId     用户标识
     * @param memoryType 记忆类型
     * @param content    记忆内容
     * @param ts         时间戳（毫秒）
     * @param tenantId   租户标识
     * @param importance 重要性 0~1
     * @return 新记忆实例
     */
    public static L1AtomicMemory create(
            String id, String traceId, String sessionId, String userId,
            String memoryType, String content, long ts, String tenantId, double importance) {
        return new L1AtomicMemory(id, traceId, sessionId, userId, memoryType, content,
                null, ts, tenantId, importance, 0, null);
    }

    /** 记忆 ID 列表转 JSON 数组字符串（用于 L2 关联 L1）。 */
    public static String l1IdsToJson(List<String> l1Ids) {
        return "[" + String.join(",", l1Ids.stream().map(id -> "\"" + id + "\"").toList()) + "]";
    }
}
