package com.zimo.module.agentmemory.model;

/**
 * 「用户长期记忆」分页查询的单行视图：L3 画像与 L1 原子记忆 UNION 后的统一形态。
 *
 * <p>为什么需要这一层：用户长期记忆来自两张表（画像 + 原子记忆），早期做法是
 * 「两张表各取一页、在内存里合并排序再切分」，于是必须先给每张表定一个硬上限，
 * 超过即静默丢弃（缺陷 D10）。改为在 SQL 层 {@code UNION ALL} 后排序分页，
 * 就必须有一个能同时承载两表字段的行类型 —— 即本记录。</p>
 *
 * @param memoryId     记忆 ID（可能来自 L3 或 L1）
 * @param category     业务类别（L3 取 {@code persona_type}，L1 取 {@code memory_type}）
 * @param content      记忆内容
 * @param ts           记忆时间（L3 取 {@code updated_ts}，L1 取 {@code ts}）
 * @param version      画像版本号（仅 L3 有值，L1 为 {@code null}）
 * @param importance   重要性 0~1
 * @param accessCount  被召回次数
 * @param lastAccessAt 最近一次被召回时间（毫秒，可为 {@code null}）
 * @param layer        来源层：{@code l3} 或 {@code l1}
 */
public record UserMemoryRow(
        String memoryId,
        String category,
        String content,
        long ts,
        Integer version,
        double importance,
        int accessCount,
        Long lastAccessAt,
        String layer) {

    /** 来源层：L3 画像。 */
    public static final String LAYER_L3 = "l3";
    /** 来源层：L1 原子记忆。 */
    public static final String LAYER_L1 = "l1";
}
