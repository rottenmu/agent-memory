package com.zimo.module.agentmemory.governance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 一次遗忘淘汰的计划：哪些条目该被淘汰、为什么，以及哪些因为受保护而被留下。
 *
 * <p>{@code protectedCount} 与 {@code keptCount} 必须分开统计 —— 「分数低所以淘汰」与
 * 「分数低但被显式标记为重要所以不淘汰」是两件完全不同的事。若合并成一个计数器，
 * 当用户发现自己标记过的重要内容仍然被删掉时，日志里没有任何线索可查。</p>
 *
 * @param evicted        将被淘汰的条目（按分数升序，最该删的排最前）
 * @param keptCount      分数达到阈值而保留的条数
 * @param protectedCount 因显式标记重要而免于淘汰的条数
 * @param scanned        扫描的候选条数
 * @param threshold      本次使用的分数阈值
 */
public record EvictionPlan(
        List<Item> evicted, int keptCount, int protectedCount, int scanned, double threshold) {

    /** 空计划。 */
    public static final EvictionPlan EMPTY = new EvictionPlan(List.of(), 0, 0, 0, 0.0);

    /** 归一化：淘汰列表不可变。 */
    public EvictionPlan {
        evicted = evicted == null ? List.of() : List.copyOf(evicted);
    }

    /**
     * 一条将被淘汰的记忆。
     *
     * @param id            记忆 id
     * @param content       内容（供 dryRun 输出，人需要看到「删的是什么」才敢确认）
     * @param score         保留分（与召回同一公式，query 为空）
     * @param decay         时间衰减分量
     * @param importance    重要性
     * @param lastTouchedAt 最近访问时间（{@code last_access_at} 缺失时回落创建时间）
     */
    public record Item(
            String id,
            String content,
            double score,
            double decay,
            double importance,
            long lastTouchedAt) {
    }

    /**
     * 将被淘汰的条数。
     *
     * <p>{@code @JsonProperty} 必需：Jackson 只序列化 record 组件，派生访问器默认不入 JSON。</p>
     */
    @JsonProperty("evictedCount")
    public int evictedCount() {
        return evicted.size();
    }

    /** 淘汰占候选的比例；候选为空时返回 0。 */
    @JsonProperty("evictedRatio")
    public double evictedRatio() {
        return scanned <= 0 ? 0.0 : (double) evicted.size() / scanned;
    }

    /** 没有任何条目被淘汰。 */
    public boolean isEmpty() {
        return evicted.isEmpty();
    }
}
