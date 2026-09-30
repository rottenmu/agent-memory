package com.zimo.module.agentmemory.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zimo.module.agentmemory.autoconfig.AgentMemoryProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S1 混合打分的可回归性验证（对应 PRD §十一 标准 14）。
 *
 * <p>这些断言必须**逐位相同**地成立：分数一旦带随机性，后续任何「召回变好/变坏」的判断都不成立。</p>
 */
class MemoryScorerTest {

    private static final long DAY_MS = 24L * 60 * 60 * 1000L;
    private static final String QUERY = "碳化硅";
    private static final String HIT_CONTENT = "关注碳化硅行业的产能与价格走势";
    private static final String MISS_CONTENT = "喜欢喝不加糖的黑咖啡";

    private static final long NOW = 1_788_000_000_000L;

    private static MemoryScorer scorer(double halfLifeDays) {
        return new MemoryScorer(0.5, 0.3, 0.2, halfLifeDays, 8, 0.25);
    }

    /** 用默认配置构造，确认 {@code of()} 与配置解析链路一致。 */
    private static MemoryScorer defaultScorer() {
        return MemoryScorer.of(new AgentMemoryProperties.Recall(null, null, null, null));
    }

    /* ---------------- 标准 14：A > B > C ---------------- */

    @Test
    @DisplayName("标准14：含关键词且新 > 含关键词但150天未访问 > 不含关键词")
    void ranksFreshHitAboveStaleHitAboveMiss() {
        MemoryScorer scorer = defaultScorer();

        double scoreA = scorer.score(QUERY, HIT_CONTENT, 0.5, NOW, NOW).score();
        double scoreB = scorer.score(QUERY, HIT_CONTENT, 0.5, NOW - 150 * DAY_MS, NOW).score();
        double scoreC = scorer.score(QUERY, MISS_CONTENT, 0.5, NOW, NOW).score();

        assertThat(scoreA).isGreaterThan(scoreB);
        assertThat(scoreB).isGreaterThan(scoreC);
    }

    @Test
    @DisplayName("标准14：连续 3 次调用 score 逐位相同（纯函数、无随机性）")
    void isBitwiseDeterministic() {
        MemoryScorer scorer = defaultScorer();

        double first = scorer.score(QUERY, HIT_CONTENT, 0.42, NOW - 3 * DAY_MS, NOW).score();
        for (int i = 0; i < 3; i++) {
            double again = scorer.score(QUERY, HIT_CONTENT, 0.42, NOW - 3 * DAY_MS, NOW).score();
            assertThat(again).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("标准14：half-life-days 改为 1 后，B 的分数下降而 A 不变")
    void shorterHalfLifeLowersOnlyTheStaleOne() {
        double freshBefore = defaultScorer().score(QUERY, HIT_CONTENT, 0.5, NOW, NOW).score();
        double staleBefore = defaultScorer().score(QUERY, HIT_CONTENT, 0.5, NOW - 150 * DAY_MS, NOW).score();

        MemoryScorer shortHalfLife = scorer(1);
        double freshAfter = shortHalfLife.score(QUERY, HIT_CONTENT, 0.5, NOW, NOW).score();
        double staleAfter = shortHalfLife.score(QUERY, HIT_CONTENT, 0.5, NOW - 150 * DAY_MS, NOW).score();

        assertThat(freshAfter).isEqualTo(freshBefore);
        assertThat(staleAfter).isLessThan(staleBefore);
    }

    /* ---------------- 分数分解：能解释「为什么这条没被召回」 ---------------- */

    @Test
    @DisplayName("分数分解：三个分量各自独立且可解释")
    void exposesExplainableBreakdown() {
        MemoryScore hit = defaultScorer().score(QUERY, HIT_CONTENT, 1.0, NOW, NOW);

        assertThat(hit.match()).isEqualTo(1.0);
        assertThat(hit.decay()).isEqualTo(1.0);
        assertThat(hit.importance()).isEqualTo(1.0);
        assertThat(hit.score()).isEqualTo(1.0);

        MemoryScore miss = defaultScorer().score(QUERY, MISS_CONTENT, 0.0, NOW, NOW);
        assertThat(miss.match()).isZero();
        assertThat(miss.importance()).isZero();
        // 不相关但新鲜、且零重要性时仍有 0.3（新鲜度项）；这正是「不相关也会被捞出来」的原因，
        // 靠注入阈值把它挡住 —— 阈值不能省。
        assertThat(miss.score()).isCloseTo(0.3, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("分数分解：不相关 + 陈旧 + 零重要性 → 低于注入阈值，不会污染 Prompt")
    void blocksIrrelevantStaleMemoryByThreshold() {
        MemoryScorer scorer = defaultScorer();
        MemoryScore miss = scorer.score(QUERY, MISS_CONTENT, 0.0, NOW - 150 * DAY_MS, NOW);

        assertThat(miss.match()).isZero();
        assertThat(miss.score()).isLessThan(scorer.minScore());
        assertThat(scorer.meetsMinScore(miss.score())).isFalse();
    }

    @Test
    @DisplayName("match 不是语义相似度：改用词表达同一意思匹配不上（刻意不叫 similarity）")
    void matchIsLexicalNotSemantic() {
        // 「第三代半导体」与「碳化硅」语义相关，但字符级 bigram 覆盖不到 —— 这是 S1 的已知边界，
        // 也正是字段命名为 match 而非 similarity 的原因。
        double score = defaultScorer().match(QUERY, "第三代半导体的行业景气度");
        assertThat(score).isZero();
    }

    /* ---------------- match 与 decay 的边界 ---------------- */

    @Test
    @DisplayName("match：查询为空 / 内容为空 / 含查询串 / 单字查询")
    void matchBoundaries() {
        MemoryScorer scorer = defaultScorer();

        assertThat(scorer.match(null, HIT_CONTENT)).isZero();
        assertThat(scorer.match("", HIT_CONTENT)).isZero();
        assertThat(scorer.match(QUERY, null)).isZero();

        // 整串包含给 0.4 基线，bigram 全覆盖再给 0.6
        assertThat(scorer.match(QUERY, HIT_CONTENT)).isEqualTo(1.0);
        // 单个「碳」字不构成 bigram，退化为纯包含判定（0.4）
        assertThat(scorer.match("碳", HIT_CONTENT)).isEqualTo(0.4);
    }

    @Test
    @DisplayName("decay：恰好一个半衰期 = 0.5；时间未知按新鲜处理；未来时间不放大")
    void decayBoundaries() {
        MemoryScorer scorer = scorer(30);

        assertThat(scorer.decay(NOW - 30 * DAY_MS, NOW)).isEqualTo(0.5);
        assertThat(scorer.decay(NOW, NOW)).isEqualTo(1.0);
        assertThat(scorer.decay(0, NOW)).isEqualTo(1.0);
        assertThat(scorer.decay(NOW + 10 * DAY_MS, NOW)).isEqualTo(1.0);
        assertThat(scorer.decay(NOW - 60 * DAY_MS, NOW)).isEqualTo(0.25);
    }

    @Test
    @DisplayName("decay：同一小时内的时间抖动不改变分数（分数逐位可复现的前提）")
    void decayIsStableWithinSameHour() {
        MemoryScorer scorer = scorer(30);
        long lastTouched = NOW - 150 * DAY_MS;

        // 两分钟后再次调用：若不量化 Δt，这里会得到不同的小数（毫秒抖动导致第 10 位漂移）
        assertThat(scorer.decay(lastTouched, NOW + 2 * 60 * 1000))
                .isEqualTo(scorer.decay(lastTouched, NOW));

        // 刚写入的条目在同一小时内恒为满分新鲜度，而不是 0.99999999xx
        assertThat(scorer.decay(NOW, NOW + 30 * 60 * 1000)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("importance 越界值被夹到 0~1，不放大总分")
    void clampsImportance() {
        MemoryScorer scorer = defaultScorer();

        assertThat(scorer.score(QUERY, MISS_CONTENT, 5.0, NOW, NOW).importance()).isEqualTo(1.0);
        assertThat(scorer.score(QUERY, MISS_CONTENT, -3.0, NOW, NOW).importance()).isZero();
        assertThat(scorer.score(QUERY, MISS_CONTENT, Double.NaN, NOW, NOW).importance()).isZero();
    }

    @Test
    @DisplayName("总分恒落在 0~1（权重和为 1 的直接推论）")
    void totalAlwaysWithinUnitRange() {
        MemoryScorer scorer = defaultScorer();
        List<Double> scores = List.of(
                scorer.score(QUERY, HIT_CONTENT, 1.0, NOW, NOW).score(),
                scorer.score(QUERY, HIT_CONTENT, 0.0, 0, NOW).score(),
                scorer.score("", "", 0.0, 0, NOW).score());
        assertThat(scores).allSatisfy(s -> assertThat(s).isBetween(0.0, 1.0));
    }

    /* ---------------- 配置接线 ---------------- */

    @Test
    @DisplayName("MemoryScorer.of 读取的 topK / minScore / halfLife 与配置一致")
    void readsParametersFromConfiguration() {
        MemoryScorer scorer = MemoryScorer.of(
                new AgentMemoryProperties.Recall(List.of(0.4, 0.4, 0.2), 7, 3, 0.6));

        assertThat(scorer.topK()).isEqualTo(3);
        assertThat(scorer.halfLifeDays()).isEqualTo(7);
        assertThat(scorer.minScore()).isEqualTo(0.6);
        assertThat(scorer.meetsMinScore(0.6)).isTrue();
        assertThat(scorer.meetsMinScore(0.59)).isFalse();
    }

    @Test
    @DisplayName("构造参数非法时立即失败（不带着无意义的打分运行）")
    void rejectsInvalidConstructorArguments() {
        assertThatThrownBy(() -> new MemoryScorer(0.5, 0.3, 0.2, 0, 8, 0.25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("halfLifeDays");
        assertThatThrownBy(() -> new MemoryScorer(0.5, 0.3, 0.2, 30, 0, 0.25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK");
    }

    @Test
    @DisplayName("算法标识恒为 s1_hybrid（S2 上线后用于 A/B）")
    void exposesAlgorithmTag() {
        assertThat(MemoryScorer.ALGORITHM_S1_HYBRID).isEqualTo("s1_hybrid");
    }
}
