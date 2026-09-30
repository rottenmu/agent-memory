package com.zimo.module.agentmemory.governance;

import com.zimo.module.agentmemory.autoconfig.AgentMemoryProperties;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * S1 可解释混合打分器：召回与遗忘淘汰**共用同一口径**。
 *
 * <p>实现规格见 {@code docs/plans/2026-09-15-agent-memory-module-prd.md} §3.2。三项均已归一化到 0~1，
 * 因此加权和天然落在 0~1：</p>
 *
 * <pre>
 * score = w_match × match + w_decay × decay + w_importance × importance
 * match  = 0.6 × bigramOverlap(query, content) + 0.4 × (content 含 query ? 1 : 0)
 * decay  = 0.5 ^ (Δt / halfLife)      Δt = now - 记忆最近被访问时间
 * </pre>
 *
 * <p><b>为什么字段叫 {@code match} 而不是 {@code similarity}</b>：{@link #bigramOverlap} 是字符级
 * 2-gram 覆盖率，<b>不是语义相似度</b>。中文没有空格分词，单字命中会被「的 / 了 / 是」这类高频虚词
 * 主导（几乎任何句子都能与任意记忆「命中」）；bigram 对虚词的贡献天然更小，且零依赖、可离线回归。
 * 命名诚实是这套方案能被信任的前提 —— 避免让人误以为系统具备向量语义召回能力。</p>
 *
 * <p><b>纯函数</b>：本类不持有可变状态、不做 IO、不读时钟（{@code now} 由调用方传入），
 * 因此同样的输入必然得到逐位相同的输出 —— 这是「分数可回归」这条验收标准能成立的基础。</p>
 */
public final class MemoryScorer {

    /**
     * 召回算法标识，写入埋点属性 {@code memory.recall.algorithm}。
     *
     * <p>本期恒为 {@code s1_hybrid}。将来上线 S2（真语义向量）后此值变为 {@code s2_vector}，
     * 用于 A/B 对比 —— 没有这个字段就无法验证 S2 是否真的更好（PRD §3.2 尾注）。</p>
     */
    public static final String ALGORITHM_S1_HYBRID = "s1_hybrid";

    private static final long DAY_MS = 24L * 60 * 60 * 1000L;

    /**
     * 衰减时间轴的量化粒度（1 小时）。
     *
     * <p>存在的唯一目的是让分数在同一小时内逐位可复现（原因与代价见 {@link #decay}）。
     * 改小会放大毫秒抖动，改大会让「刚写过」与「一整天前写过」拿到同样的新鲜度。</p>
     */
    private static final long DECAY_GRANULARITY_MS = 60L * 60 * 1000L;

    /** {@code match} 内部权重：bigram 覆盖率占 0.6，整串包含关系占 0.4。 */
    private static final double MATCH_BIGRAM_WEIGHT = 0.6;
    /** {@code match} 内部权重：整串包含关系占 0.4。 */
    private static final double MATCH_CONTAINS_WEIGHT = 0.4;

    private final double matchWeight;
    private final double decayWeight;
    private final double importanceWeight;
    private final double halfLifeDays;
    private final int topK;
    private final double minScore;

    /**
     * @param matchWeight      查询相关性权重
     * @param decayWeight      时间新鲜度权重
     * @param importanceWeight 条目重要性权重
     * @param halfLifeDays     时间衰减半衰期（天），必须 &gt; 0
     * @param topK             召回条数上限，必须 &gt; 0
     * @param minScore         注入阈值，0~1
     */
    public MemoryScorer(
            double matchWeight,
            double decayWeight,
            double importanceWeight,
            double halfLifeDays,
            int topK,
            double minScore) {
        if (halfLifeDays <= 0) {
            throw new IllegalArgumentException("halfLifeDays 必须大于 0，当前为 " + halfLifeDays);
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必须大于 0，当前为 " + topK);
        }
        this.matchWeight = matchWeight;
        this.decayWeight = decayWeight;
        this.importanceWeight = importanceWeight;
        this.halfLifeDays = halfLifeDays;
        this.topK = topK;
        this.minScore = minScore;
    }

    /**
     * 按配置构造。
     *
     * @param recall 召回参数（权重的合法性已在配置构造期校验，见
     *               {@link AgentMemoryProperties.Recall}）
     */
    public static MemoryScorer of(AgentMemoryProperties.Recall recall) {
        return new MemoryScorer(
                recall.matchWeight(),
                recall.decayWeight(),
                recall.importanceWeight(),
                recall.halfLifeDays(),
                recall.topK(),
                recall.minScore());
    }

    /* ---------------- 对外打分 ---------------- */

    /**
     * 计算综合分，并返回三项分解（供埋点与调试解释「为什么这条排前面」）。
     *
     * @param query        查询文本；为空表示不做相关性匹配（{@code match} = 0）
     * @param content      记忆内容
     * @param importance   条目重要性（会夹到 0~1）
     * @param lastTouchedAt 记忆最近被访问 / 创建的时间戳（毫秒）；&le; 0 表示未知
     * @param now          当前时间戳（毫秒），由调用方传入以保证可回归
     * @return 分数分解
     */
    public MemoryScore score(
            String query, String content, double importance, long lastTouchedAt, long now) {
        double matchValue = match(query, content);
        double decayValue = decay(lastTouchedAt, now);
        double importanceValue = clamp01(importance);
        double total = matchWeight * matchValue
                + decayWeight * decayValue
                + importanceWeight * importanceValue;
        return new MemoryScore(total, matchValue, decayValue, importanceValue);
    }

    /**
     * 查询相关性：{@code 0.6 × bigram 覆盖率 + 0.4 × 整串包含}。
     *
     * <p>两个分量都是「内容覆盖查询」的方向（分母取查询侧），刻意不做对称化：召回关心的是
     * 「这段内容是否答得上这个查询」，而不是两段文本互为镜像的程度。</p>
     *
     * @return 0~1
     */
    public double match(String query, String content) {
        String q = normalize(query);
        String c = normalize(content);
        if (q.isEmpty() || c.isEmpty()) {
            return 0.0;
        }
        double bigram = bigramOverlap(normalizedSet(q), normalizedSet(c));
        double contains = c.contains(q) ? 1.0 : 0.0;
        return MATCH_BIGRAM_WEIGHT * bigram + MATCH_CONTAINS_WEIGHT * contains;
    }

    /**
     * 时间衰减：{@code 0.5 ^ (Δt / halfLife)}。
     *
     * <p><b>Δt 量化到整小时</b>：不量化的话，{@code Δt = now - lastTouchedAt} 里的
     * {@code now} 每次调用都不同（毫秒级抖动），同一个条目两次召回的分数会在第 10 位
     * 小数上漂移（实测 {@code 0.6399999996790985} vs {@code 0.6399999994384225}）。
     * 这会让「分数可回归」这条验收标准（PRD §十一 标准 14：连续 3 次调用 score 逐位相同）
     * <b>永远无法成立</b> —— 漂移不是随机数造成的，而是时钟造成的，靠改测试放宽断言
     * 只会把问题藏起来。</p>
     *
     * <p>量化代价可忽略：半衰期默认 30 天，一小时带来的衰减误差为
     * {@code 1 - 0.5^(1/720)} ≈ 0.096%，远小于权重本身的近似程度。收益是分数在
     * <b>同一小时内逐位可复现</b>，让召回质量可以被真正对比。</p>
     *
     * @param lastTouchedAt 记忆最近被访问 / 创建的时间戳（毫秒）；&le; 0 表示未知
     * @param now           当前时间戳（毫秒）
     * @return 0~1；恰好经过一个半衰期时为 0.5
     */
    public double decay(long lastTouchedAt, long now) {
        if (lastTouchedAt <= 0) {
            // 时间未知时按「新鲜」处理：不因缺字段而误判为陈旧（召回侧偏保守，淘汰侧偏宽容）
            return 1.0;
        }
        long delta = now - lastTouchedAt;
        if (delta <= 0) {
            return 1.0;
        }
        long quantized = (delta / DECAY_GRANULARITY_MS) * DECAY_GRANULARITY_MS;
        return Math.pow(0.5, (double) quantized / (halfLifeDays * DAY_MS));
    }

    /**
     * 查询 2-gram 被内容覆盖的比例：{@code |Q ∩ C| / |Q|}。
     *
     * <p>查询串去重后不足 2 个字符时没有 bigram，返回 0 —— 此时相关性完全由
     * {@link #match} 的整串包含分量决定（0.4 分上限），不会因分母为 0 而炸掉。</p>
     *
     * @return 0~1
     */
    public static double bigramOverlap(Set<String> queryBigrams, Set<String> contentBigrams) {
        if (queryBigrams.isEmpty()) {
            return 0.0;
        }
        int hit = 0;
        for (String bigram : queryBigrams) {
            if (contentBigrams.contains(bigram)) {
                hit++;
            }
        }
        return (double) hit / queryBigrams.size();
    }

    /**
     * 取字符串的全部相邻 2-gram（去重，保留出现顺序）。
     *
     * @param text 已归一化的文本
     */
    public static Set<String> normalizedSet(String text) {
        Set<String> bigrams = new LinkedHashSet<>();
        if (text == null || text.length() < 2) {
            return bigrams;
        }
        for (int i = 0; i + 1 < text.length(); i++) {
            bigrams.add(text.substring(i, i + 2));
        }
        return bigrams;
    }

    /* ---------------- 参数 ---------------- */

    /** 召回条数上限。 */
    public int topK() {
        return topK;
    }

    /** 注入阈值：低于此分不注入 Prompt。 */
    public double minScore() {
        return minScore;
    }

    /** 衰减半衰期（天）。 */
    public double halfLifeDays() {
        return halfLifeDays;
    }

    /** 分数是否达到注入阈值。 */
    public boolean meetsMinScore(double score) {
        return score >= minScore;
    }

    /* ---------------- 内部工具 ---------------- */

    /** 归一化：去首尾空白、转小写、把连续空白折叠成单空格（避免换行破坏 bigram 连续性推断）。 */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
