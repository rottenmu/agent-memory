package com.zimo.module.agentmemory.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zimo.module.agentmemory.model.L1AtomicMemory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link MemoryEvictor} 单测。
 *
 * <p>对应 PRD §7.3「遗忘淘汰」。淘汰是**删数据**，断言因此集中在三条防线上：
 * 低分才删、显式标记重要的绝不删、以及「一次删太多」必须能被识别为可疑
 * （最常见的成因是阈值配错，而不是记忆真的该清了）。</p>
 */
class MemoryEvictorTest {

    private static final long DAY = 24L * 60 * 60 * 1000L;
    private static final long NOW = 1_700_000_000_000L;

    private static MemoryEvictor evictor() {
        return MemoryEvictor.of(new MemoryScorer(0.5, 0.3, 0.2, 30, 8, 0.25));
    }

    /** 组装一条 L1 条目。 */
    private static L1AtomicMemory item(
            String id, String content, double importance, long ts, Long lastAccessAt) {
        return new L1AtomicMemory(id, "trace-" + id, "s1", "u1", "history", content,
                null, ts, "t1", importance, 0, lastAccessAt);
    }

    /** 一条长期未访问、重要性中性的条目。 */
    private static L1AtomicMemory stale(String id, double importance) {
        return item(id, "很久以前的事实 " + id, importance, NOW - 200 * DAY, NOW - 200 * DAY);
    }

    @Nested
    @DisplayName("淘汰判定")
    class Decision {

        @Test
        @DisplayName("低分且未受保护 → 淘汰")
        void evictsLowScoreUnprotected() {
            EvictionPlan plan = evictor().plan(List.of(stale("a", 0.1)), NOW, 0.15);

            assertThat(plan.evictedCount()).isEqualTo(1);
            assertThat(plan.evicted().get(0).id()).isEqualTo("a");
            assertThat(plan.keptCount()).isZero();
            assertThat(plan.scanned()).isEqualTo(1);
        }

        @Test
        @DisplayName("分数达阈值 → 保留")
        void keepsFreshEnough() {
            // 10 天前访问过：decay ≈ 0.79，score ≈ 0.34，高于 0.15
            EvictionPlan plan = evictor().plan(
                    List.of(item("fresh", "最近还在用的事实", 0.5, NOW - 10 * DAY, NOW - 10 * DAY)),
                    NOW, 0.15);

            assertThat(plan.isEmpty()).isTrue();
            assertThat(plan.keptCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("显式标记重要 → 分数再低也保留，并单独计数")
        void protectsExplicitlyImportant() {
            // 阈值提高到 0.5，使 0.95 重要性的条目也落在「低分」区间
            EvictionPlan plan = evictor().plan(List.of(stale("pinned", 0.95)), NOW, 0.5);

            assertThat(plan.isEmpty()).isTrue();
            assertThat(plan.protectedCount()).isEqualTo(1);
            assertThat(plan.keptCount()).isZero();
        }

        @Test
        @DisplayName("受保护与分数达标是两种不同的保留原因，必须分开计数")
        void separatesProtectedFromKept() {
            // 三条各自落在不同判定分支：
            //   kept   —— 刚访问过，分数 0.40 ≥ 0.30，靠分数留下
            //   pinned —— 久未访问导致分数只有 0.19，但显式标记为重要，靠保护留下
            //   victim —— 久未访问、重要性低、未受保护，淘汰
            EvictionPlan plan = evictor().plan(List.of(
                    item("kept", "最近还在用的事实", 0.5, NOW - DAY, NOW - DAY),
                    stale("pinned", 0.95),
                    stale("victim", 0.1)), NOW, 0.30);

            assertThat(plan.evictedCount()).isEqualTo(1);
            assertThat(plan.evicted().get(0).id()).isEqualTo("victim");
            assertThat(plan.protectedCount()).isEqualTo(1);
            assertThat(plan.keptCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("last_access_at 缺失时回落创建时间，不被当成「刚访问过」")
        void fallsBackToCreatedAtWhenNeverAccessed() {
            EvictionPlan plan = evictor().plan(
                    List.of(item("never", "从未被召回的事实", 0.1, NOW - 200 * DAY, null)),
                    NOW, 0.15);

            assertThat(plan.evictedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("闲置越久分数越低")
        void olderScoresLower() {
            EvictionPlan plan = evictor().plan(List.of(
                    item("recent", "同一件事的两种说法", 0.5, NOW - 60 * DAY, NOW - 60 * DAY),
                    item("ancient", "同一件事的两种说法", 0.5, NOW - 300 * DAY, NOW - 300 * DAY)),
                    NOW, 0.15);

            List<String> evictedIds = plan.evicted().stream()
                    .map(EvictionPlan.Item::id).toList();
            assertThat(evictedIds).containsExactly("ancient");
        }
    }

    @Nested
    @DisplayName("计划形态")
    class PlanShape {

        @Test
        @DisplayName("淘汰清单按分数升序 —— 最该删的排最前，便于人工审查 dryRun")
        void ordersEvictedByScoreAscending() {
            EvictionPlan plan = evictor().plan(List.of(
                    stale("higher", 0.1),
                    stale("lower", 0.02)), NOW, 0.15);

            assertThat(plan.evicted()).extracting(EvictionPlan.Item::id)
                    .containsExactly("lower", "higher");
            assertThat(plan.evicted().get(0).score())
                    .isLessThan(plan.evicted().get(1).score());
        }

        @Test
        @DisplayName("阈值取 0 时全部保留（没有任何分数会低于 0）")
        void zeroThresholdKeepsEverything() {
            EvictionPlan plan = evictor().plan(List.of(stale("a", 0.1), stale("b", 0.2)), NOW, 0.0);

            assertThat(plan.isEmpty()).isTrue();
            assertThat(plan.keptCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("阈值取 1 时未受保护者全部淘汰")
        void maxThresholdEvictsAllUnprotected() {
            EvictionPlan plan = evictor().plan(List.of(
                    stale("a", 0.1),
                    stale("pinned", 0.95)), NOW, 1.0);

            assertThat(plan.evictedCount()).isEqualTo(1);
            assertThat(plan.protectedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("候选为空时返回空计划")
        void handlesEmptyCandidates() {
            MemoryEvictor evictor = evictor();

            assertThat(evictor.plan(List.of(), NOW, 0.15).isEmpty()).isTrue();
            assertThat(evictor.plan(null, NOW, 0.15).scanned()).isZero();
        }

        @Test
        @DisplayName("内容为 null 不抛异常")
        void toleratesNullContent() {
            EvictionPlan plan = evictor().plan(
                    List.of(item("null-content", null, 0.1, NOW - 200 * DAY, NOW - 200 * DAY)),
                    NOW, 0.15);

            assertThat(plan.evictedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("淘汰条目带出可解释的三项分解")
        void exposesExplainableBreakdown() {
            EvictionPlan plan = evictor().plan(List.of(stale("a", 0.1)), NOW, 0.15);

            EvictionPlan.Item item = plan.evicted().get(0);
            assertThat(item.decay()).isLessThan(0.05);
            assertThat(item.importance()).isEqualTo(0.1);
            assertThat(item.lastTouchedAt()).isEqualTo(NOW - 200 * DAY);
            assertThat(item.score()).isCloseTo(0.3 * item.decay() + 0.2 * 0.1, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        @DisplayName("计划本身可回归")
        void isDeterministic() {
            MemoryEvictor evictor = evictor();
            List<L1AtomicMemory> candidates = List.of(stale("a", 0.1), stale("b", 0.02));

            assertThat(evictor.plan(candidates, NOW, 0.15).evicted())
                    .isEqualTo(evictor.plan(candidates, NOW, 0.15).evicted());
        }
    }

    @Nested
    @DisplayName("闲置窗口与安全阀")
    class CutoffAndSafety {

        @Test
        @DisplayName("闲置截止时间 = 当前 - 最小闲置天数")
        void computesCutoff() {
            assertThat(evictor().idleCutoff(NOW))
                    .isEqualTo(NOW - (long) MemoryEvictor.DEFAULT_MIN_IDLE_DAYS * DAY);
        }

        @Test
        @DisplayName("单次淘汰比例过高时标记可疑 —— 多半是阈值配错，而不是记忆真该清")
        void flagsSuspiciousHighRatio() {
            List<EvictionPlan.Item> evicted = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                evicted.add(new EvictionPlan.Item("id" + index, "c", 0.1, 0.1, 0.1, 0L));
            }
            EvictionPlan suspicious = new EvictionPlan(evicted, 7, 0, 10, 0.15);

            assertThat(suspicious.evictedRatio()).isEqualTo(0.3);
            assertThat(new MemoryEvictor.Outcome(suspicious, 3, false).suspicious()).isTrue();
        }

        @Test
        @DisplayName("正常比例不报警")
        void normalRatioIsNotSuspicious() {
            EvictionPlan normal = new EvictionPlan(
                    List.of(new EvictionPlan.Item("a", "c", 0.1, 0.1, 0.1, 0L)), 99, 0, 100, 0.15);

            assertThat(new MemoryEvictor.Outcome(normal, 1, false).suspicious()).isFalse();
        }

        @Test
        @DisplayName("候选为空时比例为 0，不误报警")
        void emptyPlanRatioIsZero() {
            assertThat(EvictionPlan.EMPTY.evictedRatio()).isZero();
            assertThat(new MemoryEvictor.Outcome(EvictionPlan.EMPTY, 0, false).suspicious()).isFalse();
        }
    }

    @Nested
    @DisplayName("构造校验")
    class Construction {

        @Test
        @DisplayName("缺少打分器直接失败 —— 淘汰与召回必须同一口径")
        void requiresScorer() {
            assertThatThrownBy(() -> new MemoryEvictor(null, 90))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("同一口径");
        }

        @Test
        @DisplayName("最小闲置天数必须为正")
        void rejectsNonPositiveIdleDays() {
            MemoryScorer scorer = new MemoryScorer(0.5, 0.3, 0.2, 30, 8, 0.25);

            assertThatThrownBy(() -> new MemoryEvictor(scorer, 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new MemoryEvictor(scorer, -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
