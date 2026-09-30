package com.zimo.module.agentmemory.engine;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 一条链路的记忆读写记录（PRD §8.3，端点 {@code GET /api/ai/memory/span/{traceId}}）。
 *
 * <p>所属模块：module-agent-memory 记忆感知引擎。</p>
 *
 * <h2>为什么必须合并两类数据源</h2>
 * <p>只给 span 不行：异步回写按设计<b>不</b>发 span（{@code GenAiTraceObserver} 在
 * {@code onEnd} 已把 span 列表取走并导出，之后再追加会「不导出 + 不释放」），所以
 * 「异步到底写成了没有」只能从审计表读。</p>
 * <p>只给审计表也不行：审计表是为长期统计设计的窄表（无 {@code algorithm} 等字段），
 * 而「这次召回用的是哪个算法、截断了没有」是排查召回质量时的必要信息，只在 span 属性里。</p>
 * <p>因此两者合并返回，并靠 {@code traceId} 对齐：{@code spans} 来自链路侧，{@code audit}
 * 来自落库侧，{@code summary} 给出可直接断言的计数。</p>
 *
 * <h2>范围声明（不要误读）</h2>
 * <p>{@code spans} 是<b>本模块发出的记忆 span</b>，不是整条链路的全部 span。完整链路
 * （模型调用、工具调用等）由观测模块的链路详情接口提供，本端点只负责回答
 * 「这条链路用到了哪些记忆、写得怎么样」。</p>
 *
 * <p>另外，{@code spans} 是<b>进程内留存</b>的（只保留最近若干条链路），审计表才是持久化事实。
 * 进程重启后 {@code spans} 为空而 {@code audit} 仍在 —— 这是设计如此，不是丢数据。</p>
 *
 * @param traceId 链路 ID
 * @param spans   本模块发往链路的 span 副本（时间序；同步阶段产生）
 * @param audit   审计表记录（时间序，同一毫秒按自增 id 兜底排序）
 * @param summary 计数汇总（可直接用于断言）
 * @author zimo
 * @since 2026-09-16
 */
public record MemorySpanTrace(
        String traceId,
        List<MemorySpanRecorder.MemorySpanView> spans,
        List<MemorySpanRecord> audit,
        Summary summary) {

    /**
     * 计数汇总。
     *
     * @param chainSpanCount    链路里本模块发出的 span 数（同步阶段）
     * @param auditCount        审计表记录总数
     * @param syncCount         审计记录中同步阶段（{@code phase=sync}）的条数
     * @param asyncCount        审计记录中异步阶段（{@code phase=async}）的条数
     * @param pendingCount      审计记录中等待异步完成的条数（{@code status=pending}）
     * @param writtenCount      异步写入完成的条数（{@code operation=write} 且
     *                          {@code phase=async} 且 {@code status=ok}）
     * @param emittedAsyncCount 进程内异步阶段发出的 span 数；<b>恒为 0 才是正确状态</b>
     */
    public record Summary(
            int chainSpanCount,
            int auditCount,
            int syncCount,
            int asyncCount,
            int pendingCount,
            int writtenCount,
            int emittedAsyncCount) {
    }

    /**
     * 由原始数据组装（计数在这里算，控制器只做协议转换）。
     *
     * @param traceId           链路 ID
     * @param spans             本模块发出的 span 副本
     * @param audit             审计表记录
     * @param emittedAsyncCount 进程内异步阶段发出的 span 累计数
     * @return 合并视图
     */
    public static MemorySpanTrace of(String traceId, List<MemorySpanRecorder.MemorySpanView> spans,
                                     List<MemorySpanRecord> audit, int emittedAsyncCount) {
        List<MemorySpanRecorder.MemorySpanView> safeSpans = spans == null ? List.of() : spans;
        List<MemorySpanRecord> safeAudit = audit == null ? List.of() : audit;
        int sync = 0;
        int async = 0;
        int pending = 0;
        int written = 0;
        for (MemorySpanRecord row : safeAudit) {
            if (row.async()) {
                async++;
                if (MemorySpanAttributes.OP_WRITE.equals(row.operation())
                        && MemorySpanAttributes.STATUS_OK.equals(row.status())) {
                    written++;
                }
            } else {
                sync++;
            }
            if (row.pending()) {
                pending++;
            }
        }
        return new MemorySpanTrace(traceId, safeSpans, safeAudit,
                new Summary(safeSpans.size(), safeAudit.size(), sync, async, pending, written,
                        emittedAsyncCount));
    }

    /**
     * 异步回写是否已经落地（标准 9 的判据位）。
     *
     * <p>必须带 {@code @JsonProperty}：Jackson 序列化 record 只认 record 组件，
     * 派生方法不加注解就不会出现在接口响应里。</p>
     *
     * @return 同步骨架与异步结果都齐了才为 {@code true}
     */
    @JsonProperty("asyncArrived")
    public boolean asyncArrived() {
        return summary.pendingCount() > 0 && summary.writtenCount() > 0;
    }

    /**
     * 链路里是否出现了召回 span（标准 8 的判据位）。
     *
     * @return 存在 {@code stepType=retrieval} 的 span 时为 {@code true}
     */
    @JsonProperty("recallSpanPresent")
    public boolean recallSpanPresent() {
        for (MemorySpanRecorder.MemorySpanView span : spans) {
            if ("retrieval".equals(span.stepType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 链路是否被异步阶段污染（正确状态恒为 {@code false}）。
     *
     * <p>该判断用<b>进程内累计值</b>而非本链路的 span 列表：异步发 span 属于「绝不该发生」
     * 的路径，一旦出现过就必须在任何一条查询里都能看到，而不是只在出事那条链路里可见。</p>
     *
     * @return 进程内曾出现异步阶段发 span 时为 {@code true}
     */
    @JsonProperty("chainPollutedByAsync")
    public boolean chainPollutedByAsync() {
        return summary.emittedAsyncCount() > 0;
    }
}
