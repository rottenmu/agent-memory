package com.zimo.module.agentmemory.model;

import java.util.List;

/**
 * L1 原子记忆：从原始日志中抽取的不可再分的最小记忆单元。
 *
 * <p>例如「用户偏好报表格式」「某单据已完成审批」「客户要求周报邮件」。
 * 携带 {@code traceId} 溯源到 L0；{@code embedding} 预留向量（降级方案用
 * 纯 Java 向量计算，不依赖外部向量库）。</p>
 *
 * @param id         记忆 ID
 * @param traceId    溯源 ID（指向 L0 原始日志）
 * @param sessionId  会话标识
 * @param userId     用户标识
 * @param memoryType 记忆类型：preference / fact / habit / task / custom
 * @param content    记忆内容
 * @param embedding  内容向量（可空；由 {@code PureJavaVectorUtil} 计算）
 * @param ts         抽取时间戳（毫秒）
 */
public record L1AtomicMemory(
        String id,
        String traceId,
        String sessionId,
        String userId,
        String memoryType,
        String content,
        float[] embedding,
        long ts) {

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

    /** 记忆 ID 列表转 JSON 数组字符串（用于 L2 关联 L1）。 */
    public static String l1IdsToJson(List<String> l1Ids) {
        return "[" + String.join(",", l1Ids.stream().map(id -> "\"" + id + "\"").toList()) + "]";
    }
}
