package com.zimo.agent.memory.governance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * 记忆治理指标：把「记忆系统到底在干活没有」变成可读取的数字。
 *
 * <p>实现规格见 {@code docs/plans/2026-09-15-agent-memory-module-prd.md} §3.1「统计监控」。</p>
 *
 * <p><b>为什么召回相关的分母取自运行时而非存储</b>：「平均召回分」若从库里重算，得到的是
 * 「按当前权重重新打一遍分」的结果，而不是**那次召回实际给出的分数**。两者在有配置变更
 * 时会明显不同，而运维关心的恰恰是后者。因此召回数字由 {@link #recordRecall} 在每次召回
 * 真实发生时累加。</p>
 *
 * <p><b>进程内累加，重启即清零</b>：这是刻意接受的代价 —— 换取召回路径上零写库（PRD §3.2
 * 明确「计数更新不阻塞查询」）。因此快照里带 {@code since}，调用方必须知道这个数字
 * 是「本进程启动以来」而不是「历史累计」，否则会把重启后的低值误读成业务量下滑。</p>
 *
 * <p><b>线程安全</b>：召回发生在请求线程、治理动作发生在定时线程，累加必须无锁可并发。
 * 全部使用 {@code AtomicLong} / {@code DoubleAdder}，不使用「读-改-写」的普通字段。</p>
 *
 * <p><b>分层条数用精确统计而非扫描估算</b>：存储侧提供的是 {@code COUNT(*)} 精确值，
 * 因此快照里不设「扫描上限」字段 —— 有了精确值还暴露一个上限只会让人误以为数字被截断过。
 * 真正受上限约束的是单次召回与单次治理动作的扫描范围，那属于各自的出入参，不属这里。</p>
 *
 * @author zimo
 * @since 2026-09-15
 */
public final class MemoryMetrics {

    /**
     * 平均召回分告警线（PRD §9.4：持续低于 0.3 说明记忆与查询普遍不相关，或召回坏了）。
     */
    public static final double ALARM_AVG_SCORE = 0.3;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /* ---------------- 召回 ---------------- */
    private final AtomicLong recallCalls = new AtomicLong();
    private final AtomicLong recallHits = new AtomicLong();
    private final AtomicLong recallCandidates = new AtomicLong();
    private final AtomicLong recallReturned = new AtomicLong();
    private final DoubleAdder recallScoreSum = new DoubleAdder();

    /* ---------------- 治理 ---------------- */
    private final AtomicLong extractRuns = new AtomicLong();
    private final AtomicLong extractCandidates = new AtomicLong();
    private final AtomicLong extractDeduped = new AtomicLong();
    private final AtomicLong mergeRuns = new AtomicLong();
    private final AtomicLong mergeRemoved = new AtomicLong();
    private final AtomicLong evictRuns = new AtomicLong();
    private final AtomicLong evictDeleted = new AtomicLong();

    private final long startedAt = System.currentTimeMillis();

    /* ---------------- 记录 ---------------- */

    /**
     * 记录一次召回。
     *
     * @param candidates 参与打分的候选条数
     * @param returned   实际返回条数
     * @param scoreSum   返回条目的分数之和（用于求平均分）
     */
    public void recordRecall(int candidates, int returned, double scoreSum) {
        recallCalls.incrementAndGet();
        recallCandidates.addAndGet(Math.max(0, candidates));
        int safeReturned = Math.max(0, returned);
        recallReturned.addAndGet(safeReturned);
        if (safeReturned > 0) {
            recallHits.incrementAndGet();
        }
        if (!Double.isNaN(scoreSum)) {
            recallScoreSum.add(scoreSum);
        }
    }

    /**
     * 记录一次抽取。
     *
     * @param candidates 抽出的候选数
     * @param deduped    因重复被丢弃的候选数
     */
    public void recordExtract(int candidates, int deduped) {
        extractRuns.incrementAndGet();
        extractCandidates.addAndGet(Math.max(0, candidates));
        extractDeduped.addAndGet(Math.max(0, deduped));
    }

    /**
     * 记录一次合并。
     *
     * @param removed 被合并（删除）的条数
     */
    public void recordMerge(int removed) {
        mergeRuns.incrementAndGet();
        mergeRemoved.addAndGet(Math.max(0, removed));
    }

    /**
     * 记录一次淘汰。
     *
     * @param deleted 被淘汰（删除）的条数
     */
    public void recordEvict(int deleted) {
        evictRuns.incrementAndGet();
        evictDeleted.addAndGet(Math.max(0, deleted));
    }

    /* ---------------- 快照 ---------------- */

    /**
     * 记忆指标快照。
     *
     * <p>存储侧的四个数字由调用方传入（本类不碰存储），运行侧的九个由累加器产出。
     * {@code recallHitRate} 与 {@code recallAvgScore} 在无召回时返回 0，而不是 {@code NaN}：
     * 指标接口返回 {@code NaN} 会让前端图表整块空白，且 `NaN != NaN` 使比较失效。</p>
     *
     * @param since             进程启动时间（指标统计起点）
     * @param l0Total           L0 消息总数
     * @param l0Today           L0 今日消息数
     * @param userMemoryTotal   用户长期记忆条数
     * @param l3Count           L3 画像条数
     * @param l1Count           L1 原子记忆条数
     * @param recallCalls       召回调用次数
     * @param recallHits        有返回的召回次数（用于命中率）
     * @param recallCandidates  累计候选条数
     * @param recallReturned    累计返回条数
     * @param recallHitRate     召回命中率（有返回的调用占比）
     * @param recallAvgScore    平均召回分
     * @param extractRuns       抽取执行次数
     * @param extractCandidates 累计抽出候选数
     * @param extractDeduped    累计去重数
     * @param mergeRuns         合并执行次数
     * @param mergeRemoved      累计合并删除数
     * @param evictRuns         淘汰执行次数
     * @param evictDeleted      累计淘汰删除数
     */
    public record Snapshot(
            String since,
            long l0Total,
            long l0Today,
            int userMemoryTotal,
            int l3Count,
            int l1Count,
            long recallCalls,
            long recallHits,
            long recallCandidates,
            long recallReturned,
            double recallHitRate,
            double recallAvgScore,
            long extractRuns,
            long extractCandidates,
            long extractDeduped,
            long mergeRuns,
            long mergeRemoved,
            long evictRuns,
            long evictDeleted) {

        /**
         * 是否已有召回数据。
         *
         * <p>没有召回时不应把「命中率 0」展示成「召回了但一条都没命中」——
         * 两者对运维的含义完全相反。</p>
         *
     * <p>{@code @JsonProperty} 必需：Jackson 只序列化 record 组件，派生访问器默认不入 JSON。</p>
     *
     * @return 至少发生过一次召回时返回 {@code true}
     */
    @JsonProperty("hasRecallData")
    public boolean hasRecallData() {
            return recallCalls > 0;
        }

        /**
         * 平均召回分是否偏低（低于 PRD §9.4 的告警线 0.3）。
         *
         * <p>无召回数据时返回 {@code false}：没有数据不等于数据很差，
         * 若返回 {@code true} 会让新部署的实例一上线就告警。</p>
         *
         * @return 有召回数据且平均分低于 0.3 时返回 {@code true}
         */
        @JsonProperty("recallScoreBelowAlarmLine")
    public boolean recallScoreBelowAlarmLine() {
            return hasRecallData() && recallAvgScore < ALARM_AVG_SCORE;
        }
    }

    /**
     * 生成快照。
     *
     * @param l0Total         L0 消息总数
     * @param l0Today         L0 今日消息数
     * @param userMemoryTotal 用户长期记忆条数
     * @param l3Count         L3 画像条数
     * @param l1Count         L1 原子记忆条数
     * @return 快照
     */
    public Snapshot snapshot(
            long l0Total, long l0Today, int userMemoryTotal, int l3Count, int l1Count) {
        long calls = recallCalls.get();
        long hits = recallHits.get();
        long returned = recallReturned.get();
        double scoreSum = recallScoreSum.sum();
        return new Snapshot(
                TIME_FORMAT.format(Instant.ofEpochMilli(startedAt)),
                l0Total,
                l0Today,
                userMemoryTotal,
                l3Count,
                l1Count,
                calls,
                hits,
                recallCandidates.get(),
                returned,
                calls == 0 ? 0.0 : (double) hits / calls,
                returned == 0 ? 0.0 : scoreSum / returned,
                extractRuns.get(),
                extractCandidates.get(),
                extractDeduped.get(),
                mergeRuns.get(),
                mergeRemoved.get(),
                evictRuns.get(),
                evictDeleted.get());
    }

    /** 指标统计起点（进程启动时刻，毫秒）。 */
    public long startedAt() {
        return startedAt;
    }
}
