package com.zimo.module.agentmemory.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zimo.module.agentmemory.model.L1AtomicMemory;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link MemoryMerger} 单测。
 *
 * <p>对应 PRD §3.1「记忆合并」。合并是**不可逆**动作，因此断言格外关注三件事：
 * 该合的确实合了、不该合的一条都不能动、以及保留者是谁必须由**确定性规则**决定
 * （否则同一批数据两次治理的结果会不同）。</p>
 */
class MemoryMergerTest {

    private static MemoryMerger merger() {
        return MemoryMerger.of(new MemoryScorer(0.5, 0.3, 0.2, 30, 8, 0.25));
    }

    /** 组装一条 L1 条目。 */
    private static L1AtomicMemory item(
            String id, String category, String content, double importance, int accessCount, long ts) {
        return new L1AtomicMemory(id, "trace-" + id, "s1", "u1", category, content,
                null, ts, "t1", importance, accessCount, null);
    }

    @Nested
    @DisplayName("近似判定")
    class Similarity {

        @Test
        @DisplayName("前缀与扩展关系被判定为近似（合并的主要场景）")
        void detectsPrefixAndExtension() {
            MemoryMerger merger = merger();

            assertThat(merger.similar("以后都用 Excel", "以后都用 Excel 导出报表")).isTrue();
            assertThat(merger.similar("以后都用 Excel 导出报表", "以后都用 Excel")).isTrue();
        }

        @Test
        @DisplayName("双向对称：交换参数不改变结论")
        void isSymmetric() {
            MemoryMerger merger = merger();
            String left = "我的部门是数据平台部";
            String right = "我的团队在数据平台部";

            assertThat(merger.similar(left, right)).isEqualTo(merger.similar(right, left));
        }

        @Test
        @DisplayName("用词相近但语义不同的两条不合并")
        void keepsSemanticallyDifferentItems() {
            MemoryMerger merger = merger();

            assertThat(merger.similar("我的部门是数据平台部", "我的团队是数据平台部")).isFalse();
            assertThat(merger.similar("会议改到周四下午三点", "报表要在周五之前交付")).isFalse();
        }

        @Test
        @DisplayName("阈值越高越保守")
        void higherThresholdIsStricter() {
            MemoryScorer scorer = new MemoryScorer(0.5, 0.3, 0.2, 30, 8, 0.25);
            // 这一对用词相近但不是包含关系：match 约 0.46，恰好落在两档阈值之间
            String left = "以后都用 Excel 导出报表";
            String right = "以后都用 Excel 导出周报明细";

            assertThat(new MemoryMerger(scorer, 0.4).similar(left, right)).isTrue();
            assertThat(new MemoryMerger(scorer, 0.9).similar(left, right)).isFalse();
        }
    }

    @Nested
    @DisplayName("聚类与分组")
    class Clustering {

        @Test
        @DisplayName("同类近似条目并成一组")
        void groupsSimilarItemsOfSameCategory() {
            MemoryMerger merger = merger();
            MergePlan plan = merger.plan(List.of(
                    item("a", "preference", "以后都用 Excel", 0.7, 0, 1000L),
                    item("b", "preference", "以后都用 Excel 导出报表", 0.7, 0, 2000L)));

            assertThat(plan.groups()).hasSize(1);
            assertThat(plan.removedCount()).isEqualTo(1);
            assertThat(plan.groups().get(0).mergedIds()).containsExactly("b");
        }

        @Test
        @DisplayName("类别是合并的硬边界：文字相同也不同并")
        void neverMergesAcrossCategories() {
            MemoryMerger merger = merger();
            MergePlan plan = merger.plan(List.of(
                    item("a", "persona", "以后都用 Excel", 0.7, 0, 1000L),
                    item("b", "preference", "以后都用 Excel", 0.7, 0, 2000L)));

            assertThat(plan.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("多类别混合输入时各自独立聚类")
        void clustersEachCategoryIndependently() {
            MemoryMerger merger = merger();
            MergePlan plan = merger.plan(List.of(
                    item("a", "preference", "以后都用 Excel", 0.7, 0, 1000L),
                    item("b", "preference", "以后都用 Excel 导出", 0.7, 0, 2000L),
                    item("c", "history", "结论是采用方案 B", 0.7, 0, 3000L),
                    item("d", "history", "结论是采用方案 B 上线", 0.7, 0, 4000L)));

            assertThat(plan.groups()).hasSize(2);
            assertThat(plan.removedCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("无可合并项时返回空计划")
        void returnsEmptyPlanWhenNothingToMerge() {
            MemoryMerger merger = merger();

            assertThat(merger.plan(List.of(
                    item("a", "preference", "以后都用 Excel", 0.7, 0, 1000L),
                    item("b", "preference", "会议改到周四下午三点", 0.7, 0, 2000L))).isEmpty())
                    .isTrue();
            assertThat(merger.plan(List.of()).isEmpty()).isTrue();
            assertThat(merger.plan(null).isEmpty()).isTrue();
            assertThat(merger.plan(List.of(
                    item("a", "preference", "以后都用 Excel", 0.7, 0, 1000L))).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("空白内容的条目不参与合并")
        void skipsBlankContent() {
            MemoryMerger merger = merger();

            assertThat(merger.plan(List.of(
                    item("a", "preference", "   ", 0.7, 0, 1000L),
                    item("b", "preference", "   ", 0.7, 0, 2000L))).isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("保留者选取与结果字段")
    class KeeperSelection {

        @Test
        @DisplayName("保留重要性高者，重要性取组内最大值")
        void keepsHigherImportanceAndTakesMax() {
            MemoryMerger merger = merger();
            MergePlan plan = merger.plan(List.of(
                    item("low", "preference", "以后都用 Excel", 0.4, 0, 1000L),
                    item("high", "preference", "以后都用 Excel 导出报表", 0.8, 0, 2000L)));

            MergePlan.Group group = plan.groups().get(0);
            assertThat(group.keeperId()).isEqualTo("high");
            assertThat(group.resultingImportance()).isEqualTo(0.8);
            assertThat(group.mergedIds()).containsExactly("low");
        }

        @Test
        @DisplayName("重要性相同时保留访问次数多者")
        void prefersMoreAccessedWhenImportanceTies() {
            MemoryMerger merger = merger();
            MergePlan plan = merger.plan(List.of(
                    item("rare", "preference", "以后都用 Excel", 0.7, 0, 1000L),
                    item("often", "preference", "以后都用 Excel 导出报表", 0.7, 5, 2000L)));

            assertThat(plan.groups().get(0).keeperId()).isEqualTo("often");
        }

        @Test
        @DisplayName("重要性与访问次数都相同时保留时间更早者（避免改写原始发生时间）")
        void prefersEarlierWhenAllTies() {
            MemoryMerger merger = merger();
            MergePlan plan = merger.plan(List.of(
                    item("late", "preference", "以后都用 Excel 导出报表", 0.7, 0, 2000L),
                    item("early", "preference", "以后都用 Excel", 0.7, 0, 1000L)));

            assertThat(plan.groups().get(0).keeperId()).isEqualTo("early");
        }

        @Test
        @DisplayName("访问计数在组内累加")
        void sumsAccessCount() {
            MemoryMerger merger = merger();
            MergePlan plan = merger.plan(List.of(
                    item("a", "preference", "以后都用 Excel", 0.6, 2, 1000L),
                    item("b", "preference", "以后都用 Excel 导出报表", 0.9, 3, 2000L)));

            MergePlan.Group group = plan.groups().get(0);
            assertThat(group.keeperId()).isEqualTo("b");
            assertThat(group.resultingImportance()).isEqualTo(0.9);
            assertThat(group.resultingAccessCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("输入顺序不影响结果（治理动作必须可回归）")
        void isOrderIndependent() {
            MemoryMerger merger = merger();
            L1AtomicMemory first = item("a", "preference", "以后都用 Excel", 0.4, 1, 1000L);
            L1AtomicMemory second = item("b", "preference", "以后都用 Excel 导出报表", 0.8, 2, 2000L);

            assertThat(merger.plan(List.of(first, second)).groups())
                    .isEqualTo(merger.plan(List.of(second, first)).groups());
        }
    }

    @Nested
    @DisplayName("构造校验")
    class Construction {

        @Test
        @DisplayName("缺少打分器直接失败 —— 近似判定不能各写一套")
        void requiresScorer() {
            assertThatThrownBy(() -> new MemoryMerger(null, 0.8))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("MemoryScorer");
        }

        @Test
        @DisplayName("阈值越界直接失败")
        void rejectsInvalidThreshold() {
            MemoryScorer scorer = new MemoryScorer(0.5, 0.3, 0.2, 30, 8, 0.25);

            assertThatThrownBy(() -> new MemoryMerger(scorer, -0.1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new MemoryMerger(scorer, 1.1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
