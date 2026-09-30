package com.zimo.module.agentmemory.governance;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zimo.module.agentmemory.model.L1AtomicMemory;
import com.zimo.module.agentmemory.storage.OltpMemoryRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 遗忘淘汰器：淘汰「再也不会被召回」的记忆，把容量留给还会用到的内容。
 *
 * <p>实现规格见 {@code docs/plans/2026-09-15-agent-memory-module-prd.md} §7.3。</p>
 *
 * <p><b>「与召回同一口径」的确切含义</b>：用的是同一个 {@link MemoryScorer}、同一套权重、
 * 同一个公式，差别只在**淘汰时查询为空**，于是 {@code match} 分量为 0 ——
 * 这不是打了折扣，而恰好是「它对任何查询都匹配不上」的量化表达。低分因此同时是
 * 「召回排不上」与「该被遗忘」的判据，两处不会各说各话。</p>
 *
 * <p><b>两道保护</b>：</p>
 * <ol>
 *   <li>候选先由时间筛过（{@code 最近访问早于 N 天}）—— 近期活跃的记忆根本不进入打分区，
 *       避免「一次低分」就把刚写进来的内容删掉；</li>
 *   <li>显式标记为重要的条目（{@code importance ≥ 0.9}）即便分数低于阈值也保留 ——
 *       这是用户与智能体表达「这条绝不允许删」的唯一途径（见 {@link ImportanceScorer}）。</li>
 * </ol>
 *
 * <p><b>安全阀</b>：单次淘汰比例超过 {@link #SAFETY_RATIO} 时标记为可疑，供上层告警。
 * 一次删掉大面积记忆最常见的原因不是「记忆真的该清了」，而是阈值被配错 ——
 * 这类静默的批量删除必须留下痕迹（PRD §六 风险表）。</p>
 *
 * <p><b>计划与执行分离</b>：{@link #plan} 是纯函数，{@link #apply} 才落库。</p>
 */
public final class MemoryEvictor {

    /** 默认最小闲置天数：三个半衰期（半衰期 30 天）后仍未再被访问，方进入淘汰审查。 */
    public static final int DEFAULT_MIN_IDLE_DAYS = 90;
    /** 默认分数阈值。 */
    public static final double DEFAULT_THRESHOLD = 0.15;
    /** 单次扫描上限。 */
    public static final int DEFAULT_SCAN_LIMIT = 2_000;
    /** 单次淘汰比例告警线。 */
    public static final double SAFETY_RATIO = 0.10;

    private static final long DAY_MS = 24L * 60 * 60 * 1000L;

    /** 淘汰清单排序：分数低者在前，同分按 id 升序保证结果稳定。 */
    private static final Comparator<EvictionPlan.Item> EVICTION_ORDER =
            Comparator.comparingDouble(EvictionPlan.Item::score)
                    .thenComparing(EvictionPlan.Item::id);

    private final MemoryScorer scorer;
    private final int minIdleDays;

    /**
     * @param scorer      打分器（不得为 {@code null}：淘汰必须与召回同源）
     * @param minIdleDays 最小闲置天数，必须 &gt; 0
     */
    public MemoryEvictor(MemoryScorer scorer, int minIdleDays) {
        if (scorer == null) {
            throw new IllegalArgumentException(
                    "MemoryEvictor 需要 MemoryScorer：淘汰与召回必须同一口径");
        }
        if (minIdleDays <= 0) {
            throw new IllegalArgumentException("minIdleDays 必须大于 0，当前为 " + minIdleDays);
        }
        this.scorer = scorer;
        this.minIdleDays = minIdleDays;
    }

    /** 按默认闲置天数构造。 */
    public static MemoryEvictor of(MemoryScorer scorer) {
        return new MemoryEvictor(scorer, DEFAULT_MIN_IDLE_DAYS);
    }

    /* ---------------- 纯计算 ---------------- */

    /**
     * 闲置截止时间：早于此时间未再被访问的条目才进入淘汰审查。
     *
     * @param now 当前时间（毫秒）
     * @return 时间阈值（毫秒）
     */
    public long idleCutoff(long now) {
        return now - (long) minIdleDays * DAY_MS;
    }

    /**
     * 对候选条目产出淘汰计划（不改动任何数据）。
     *
     * @param candidates 候选条目（已由时间条件筛过）
     * @param now        当前时间（毫秒），由调用方传入以保证可回归
     * @param threshold  分数阈值；低于此值且未受保护者被淘汰
     * @return 淘汰计划
     */
    public EvictionPlan plan(List<L1AtomicMemory> candidates, long now, double threshold) {
        if (candidates == null || candidates.isEmpty()) {
            return new EvictionPlan(List.of(), 0, 0, 0, threshold);
        }
        List<EvictionPlan.Item> evicted = new ArrayList<>();
        int kept = 0;
        int protectedCount = 0;
        for (L1AtomicMemory memory : candidates) {
            if (memory == null) {
                continue;
            }
            long lastTouched = memory.lastAccessAt() != null && memory.lastAccessAt() > 0
                    ? memory.lastAccessAt() : memory.ts();
            MemoryScore score = scorer.score(
                    null, memory.content(), memory.importance(), lastTouched, now);
            if (score.score() >= threshold) {
                kept++;
            } else if (ImportanceScorer.protectedFromEviction(memory.importance())) {
                protectedCount++;
            } else {
                evicted.add(new EvictionPlan.Item(memory.id(), memory.content(),
                        score.score(), score.decay(), score.importance(), lastTouched));
            }
        }
        evicted.sort(EVICTION_ORDER);
        return new EvictionPlan(evicted, kept, protectedCount, candidates.size(), threshold);
    }

    /* ---------------- 执行 ---------------- */

    /**
     * 一次淘汰的执行结果。
     *
     * @param plan         淘汰计划
     * @param deletedCount 实际删除条数（{@code dryRun} 时为 0）
     * @param dryRun       是否为预演
     */
    public record Outcome(EvictionPlan plan, int deletedCount, boolean dryRun) {

        /**
         * 本次淘汰是否触及安全阀（比例过高，怀疑阈值配错）。
         *
     * <p>{@code @JsonProperty} 必需：Jackson 只序列化 record 组件，派生访问器默认不入 JSON。
     * 运维靠这个字段判断「是不是阈值配错了」，掉了注解等于安全阀失效。</p>
     *
     * @return 超过 {@link #SAFETY_RATIO} 时返回 {@code true}
     */
    @JsonProperty("suspicious")
    public boolean suspicious() {
            return plan.evictedRatio() > SAFETY_RATIO;
        }
    }

    /**
     * 扫描并（可选）执行淘汰。
     *
     * @param repository 仓储
     * @param tenantId   租户标识
     * @param dryRun     为 {@code true} 时只产出计划，不落库
     * @param threshold  分数阈值；{@code null} 表示用 {@link #DEFAULT_THRESHOLD}
     * @return 执行结果
     */
    public Outcome apply(
            OltpMemoryRepository repository, String tenantId, boolean dryRun, Double threshold) {
        long now = System.currentTimeMillis();
        double effective = threshold == null ? DEFAULT_THRESHOLD : threshold;
        List<L1AtomicMemory> candidates =
                repository.recallEvictionCandidates(tenantId, idleCutoff(now), DEFAULT_SCAN_LIMIT, 0);
        EvictionPlan plan = plan(candidates, now, effective);
        if (dryRun || plan.isEmpty()) {
            return new Outcome(plan, 0, dryRun);
        }
        List<String> ids = plan.evicted().stream().map(EvictionPlan.Item::id).toList();
        int deleted = repository.deleteAtomicByIds(tenantId, ids);
        return new Outcome(plan, deleted, false);
    }
}
