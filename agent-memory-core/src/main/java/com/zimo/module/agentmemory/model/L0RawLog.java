package com.zimo.module.agentmemory.model;

/**
 * L0 原始对话日志：金字塔最底层，承载可溯源的事件流。
 *
 * <p>每条 {@code trace_id} 唯一标识一次智能体交互（含工具调用链），上层
 * L1/L2/L3 记忆均携带该 ID，可反向定位到原始日志（溯源）。{@code id} 为
 * H2 自增主键，供 ETL 增量同步游标使用（插入时由数据库生成，查询映射填充）。</p>
 *
 * @param id         H2 自增主键（插入时为 0，查询映射为真实值）
 * @param traceId    溯源 ID（会话内唯一交互标识）
 * @param sessionId  会话标识
 * @param userId     用户标识
 * @param ts         事件时间戳（毫秒）
 * @param role       角色：user / assistant / tool / system
 * @param content    消息内容
 * @param tokens     token 消耗（估算）
 * @param metaJson   附加元数据 JSON（模型名、渠道、耗时等）
 */
public record L0RawLog(
        long id,
        String traceId,
        String sessionId,
        String userId,
        long ts,
        String role,
        String content,
        Integer tokens,
        String metaJson) {

    /** 供写入使用的便捷构造（id 由数据库自增生成）。 */
    public static L0RawLog forInsert(
            String traceId,
            String sessionId,
            String userId,
            long ts,
            String role,
            String content,
            Integer tokens,
            String metaJson) {
        return new L0RawLog(0L, traceId, sessionId, userId, ts, role, content, tokens, metaJson);
    }
}
