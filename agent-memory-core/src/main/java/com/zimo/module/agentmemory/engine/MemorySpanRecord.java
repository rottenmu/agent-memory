package com.zimo.module.agentmemory.engine;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 记忆审计记录（PRD §8.3，表 {@code memory_span}）。
 *
 * <p>所属模块：module-agent-memory 记忆感知引擎。<b>存在的理由</b>：异步回写按设计必然
 * 发生在链路收尾（{@code GenAiTraceObserver.onEnd} 用 {@code remove(traceId)} 取走 span 列表
 * 并导出）之后，此时再向 {@code TraceCollector} 追加 span 会「既不被导出、也不被释放」——
 * 表现为<b>看不见 + 内存泄漏</b>，而 {@code TraceCollector} 用 {@code catch (Exception ignored)}
 * 包裹回调，坏掉也不会报错。因此异步阶段的结果只落本表，绝不向已收尾的链路追加 span。</p>
 *
 * <p><b>它同时是运维指标的数据源</b>（PRD §9.4）：召回命中率 = {@code recallCount > 0} 占比、
 * 平均召回分 = {@code recallScore}、抽取成功率 = {@code status} 分布、淘汰速率 =
 * {@code operation=evict} 的计数。这些指标需要按时间趋势统计，日志做不到，必须落表。</p>
 *
 * <p><b>为什么同步阶段也要落一条（{@code phase=sync}）</b>：标准 9 的判据是「链路 span 数量
 * 不再增长，但审计表新增 1 条 async 记录」。只有同步与异步记录<b>分阶段可见</b>，
 * 该判据才可断言；否则「新增 1 条」到底是同步那条还是异步那条无从区分。</p>
 *
 * @param id          自增主键（插入时为 0，由数据库生成）
 * @param traceId     链路 ID（关联 GenAI span 的 {@code gen_ai.trace_id}）
 * @param sessionId   会话 ID
 * @param userId      用户 ID
 * @param tenantId    租户 ID（隔离审计维度）
 * @param phase       {@code sync}（链路内产生）/ {@code async}（链路收尾后产生）
 * @param operation   记忆动作，取值见 {@link MemorySpanAttributes#OP_RECALL} 等
 * @param layer       记忆层：l0 / l1 / l2 / l3 / working
 * @param memoryIds   涉及的记忆 ID（逗号分隔；超 20 个截断，避免审计行无限膨胀）
 * @param importance  重要性（0~1，可空）
 * @param recallScore 召回分（可空）
 * @param recallCount 召回条数（可空）
 * @param truncated   是否被上限截断（可空）
 * @param latencyMs   耗时（可空）
 * @param status      {@code pending} / {@code ok} / {@code failed} / {@code denied} / {@code dropped}
 * @param ts          事件时间戳（毫秒）
 */
public record MemorySpanRecord(
        long id,
        String traceId,
        String sessionId,
        String userId,
        String tenantId,
        String phase,
        String operation,
        String layer,
        String memoryIds,
        Double importance,
        Double recallScore,
        Integer recallCount,
        Boolean truncated,
        Long latencyMs,
        String status,
        long ts) {

    /** 批量 ID 在审计行里的截断条数（超出丢弃并附省略号，保留「有多少个」的信息）。 */
    static final int MAX_AUDIT_IDS = 20;

    /** 供写入使用的便捷构造（id 由数据库自增生成）。 */
    public static MemorySpanRecord forInsert(
            String traceId, String sessionId, String userId, String tenantId, String phase,
            String operation, String layer, String memoryIds, Double importance,
            Double recallScore, Integer recallCount, Boolean truncated, Long latencyMs,
            String status, long ts) {
        return new MemorySpanRecord(0L, traceId, sessionId, userId, tenantId, phase, operation,
                layer, memoryIds, importance, recallScore, recallCount, truncated, latencyMs,
                status, ts);
    }

    /**
     * 是否为异步阶段记录。
     *
     * <p>必须带 {@code @JsonProperty}：Jackson 序列化 record 时<b>只看 record 组件</b>，
     * 派生访问器一律丢弃 —— 不带注解的话本方法不会出现在接口响应里，前端永远拿不到该字段。</p>
     */
    @JsonProperty("async")
    public boolean async() {
        return MemorySpanAttributes.PHASE_ASYNC.equals(phase);
    }

    /** 是否等待异步完成（同步骨架记录为 {@code true}）。 */
    @JsonProperty("pending")
    public boolean pending() {
        return MemorySpanAttributes.STATUS_PENDING.equals(status);
    }

    /** 是否产生了记忆命中（审计侧统计召回命中率用）。 */
    @JsonProperty("hit")
    public boolean hit() {
        return recallCount != null && recallCount > 0;
    }
}
