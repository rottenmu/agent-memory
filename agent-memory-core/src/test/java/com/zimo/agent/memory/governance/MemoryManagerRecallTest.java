package com.zimo.agent.memory.governance;

import static com.zimo.agent.memory.governance.MemoryManagerFixture.CONTENT_A;
import static com.zimo.agent.memory.governance.MemoryManagerFixture.CONTENT_B;
import static com.zimo.agent.memory.governance.MemoryManagerFixture.CONTENT_C;
import static com.zimo.agent.memory.governance.MemoryManagerFixture.QUERY;
import static com.zimo.agent.memory.governance.MemoryManagerFixture.TENANT;
import static com.zimo.agent.memory.governance.MemoryManagerFixture.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 记忆召回测试：S1 打分可回归（PRD §十一 标准 14）、隔离、类型过滤与截断可见。
 *
 * <p>标准 14 是整套 S1 方案能被信任的前提，因此「连续多次调用分数逐位相同」必须真的断言，
 * 不能只断言排序正确 —— 排序正确但分数漂移会让召回质量无法回归对比。</p>
 */
class MemoryManagerRecallTest {

    @Test
    @DisplayName("标准 14：顺序恒为 A > B > C，且连续 3 次分数逐位相同")
    void ordersByScoreDeterministically() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_recall_order");
        fixture.seedStandard14();
        MemoryManager manager = fixture.manager();

        RecallResult first = manager.recall(TENANT, USER, QUERY, null, null);
        assertThat(first.items()).extracting(RecallResult.Item::content)
                .containsExactly(CONTENT_A, CONTENT_B, CONTENT_C);

        List<Double> baseline = scoresOf(first);
        for (int round = 2; round <= 4; round++) {
            assertThat(scoresOf(manager.recall(TENANT, USER, QUERY, null, null)))
                    .as("第 %s 次调用的分数必须与首次逐位相同（无随机性）", round)
                    .isEqualTo(baseline);
        }
    }

    @Test
    @DisplayName("标准 14：半衰期改 1 天后 B 下降而 A 不变")
    void shorterHalfLifeOnlyAgesOldItems() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_recall_halflife");
        fixture.seedStandard14();

        Map<String, Double> before = scoreByContent(fixture.manager(30));
        Map<String, Double> after = scoreByContent(fixture.manager(1));

        assertThat(after.get(CONTENT_B)).isLessThan(before.get(CONTENT_B));
        assertThat(after.get(CONTENT_A)).isEqualTo(before.get(CONTENT_A));
    }

    @Test
    @DisplayName("分数分解可解释：score = 0.5×match + 0.3×decay + 0.2×importance")
    void exposesScoreBreakdown() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_recall_breakdown");
        fixture.seedStandard14();

        RecallResult result = fixture.manager().recall(TENANT, USER, QUERY, null, null);
        RecallResult.Item hit = result.items().get(0);

        assertThat(hit.match()).isPositive();
        assertThat(hit.decay()).isBetween(0.9, 1.0);
        assertThat(hit.score()).isEqualTo(
                0.5 * hit.match() + 0.3 * hit.decay() + 0.2 * hit.importance(),
                Offset.offset(1e-9));
        assertThat(result.algorithm()).isEqualTo(MemoryScorer.ALGORITHM_S1_HYBRID);
    }

    @Test
    @DisplayName("低分条目照样返回，注入边界由 meetsThreshold 标出（阈值只管注入，不管返回）")
    void keepsLowScoreItemsAndMarksThreshold() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_recall_threshold");
        fixture.seedStandard14();

        RecallResult result = fixture.manager().recall(TENANT, USER, QUERY, null, null);

        assertThat(result.items()).hasSize(3);
        assertThat(result.minScore()).isEqualTo(0.25);
        // 注入边界是 meetsThreshold（分数达标 **且** 内容相关），不是「分数 ≥ 阈值」：
        // S1 是加权和，C 条不含查询关键词（match=0）却靠 recency/importance 拿到 0.4 分，
        // 只按分数判会让这条完全无关的记忆被注入 —— 2026-09-16 真机验证发现并修掉的缺陷。
        assertThat(result.injectedCount())
                .isEqualTo((int) result.items().stream()
                        .filter(item -> item.meetsThreshold(result.minScore())).count());
    }

    @Test
    @DisplayName("无关但够新的条目分数达标也不注入（阈值不得被 recency/importance 单独满足）")
    void unrelatedFreshItemIsNotInjectable() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_recall_relevance_floor");
        fixture.seedStandard14();

        RecallResult result = fixture.manager().recall(TENANT, USER, QUERY, null, null);
        RecallResult.Item unrelated = result.items().stream()
                .filter(item -> item.match() == 0.0)
                .findFirst()
                .orElseThrow(() -> new AssertionError("标准 14 的 C 条应不含查询关键词"));

        assertThat(unrelated.score()).isGreaterThanOrEqualTo(result.minScore());
        assertThat(unrelated.meetsThreshold(result.minScore())).isFalse();
        assertThat(result.items().stream().filter(item -> item.meetsThreshold(result.minScore())))
                .extracting(RecallResult.Item::content)
                .doesNotContain(unrelated.content());
    }

    @Test
    @DisplayName("租户隔离：同 id 用户在不同租户互不可见")
    void isolatesTenants() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_recall_tenant");
        fixture.service().saveUserMemory(TENANT, USER, "custom", "租户一的记忆");
        fixture.service().saveUserMemory("t2", USER, "custom", "租户二的记忆");

        RecallResult result = fixture.manager().recall(TENANT, USER, "记忆", null, null);

        assertThat(result.items()).extracting(RecallResult.Item::content)
                .containsExactly("租户一的记忆");
    }

    @Test
    @DisplayName("用户隔离：看不到其他用户的记忆")
    void isolatesUsers() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_recall_user");
        fixture.service().saveUserMemory(TENANT, "ua", "custom", "甲的记忆");
        fixture.service().saveUserMemory(TENANT, "ub", "custom", "乙的记忆");

        RecallResult result = fixture.manager().recall(TENANT, "ua", "记忆", null, null);

        assertThat(result.items()).extracting(RecallResult.Item::content)
                .containsExactly("甲的记忆");
    }

    @Test
    @DisplayName("types 过滤只保留指定类别，且扫描数与参与打分数之差不被隐藏")
    void filtersByTypes() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_recall_types");
        fixture.service().saveUserMemory(TENANT, USER, "custom", "自定义记忆");
        fixture.service().saveUserMemory(TENANT, USER, "history", "历史记忆");

        RecallResult result = fixture.manager()
                .recall(TENANT, USER, "记忆", List.of("history"), null);

        assertThat(result.items()).extracting(RecallResult.Item::category)
                .containsExactly("history");
        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.candidates()).isEqualTo(1);
    }

    @Test
    @DisplayName("topK 覆盖生效，超过上限时夹到上限而不是报错")
    void appliesAndClampsTopK() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_recall_topk");
        for (int i = 0; i < 12; i++) {
            fixture.insertAtomic("m" + i, "记忆内容" + i, "custom");
        }
        MemoryManager manager = fixture.manager();

        assertThat(manager.recall(TENANT, USER, "记忆", null, 3).items()).hasSize(3);
        assertThat(manager.recall(TENANT, USER, "记忆", null, null).topK()).isEqualTo(8);

        RecallResult clamped = manager.recall(TENANT, USER, "记忆", null, 10_000);
        assertThat(clamped.topK()).isEqualTo(MemoryManager.MAX_TOP_K);
        assertThat(clamped.items()).as("候选只有 12 条时不应凭空造出 100 条").hasSize(12);
    }

    @Test
    @DisplayName("候选池满时 truncated=true，未满时 false（不静默漏最优解）")
    void reportsTruncationFromScan() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_recall_truncated");
        for (int i = 0; i < MemoryManager.RECALL_CANDIDATE_LIMIT; i++) {
            fixture.insertAtomic(String.format("m%04d", i), "记忆内容" + i, "custom");
        }
        MemoryManager manager = fixture.manager();

        RecallResult full = manager.recall(TENANT, USER, "记忆", null, 5);
        assertThat(full.scanned()).isEqualTo(MemoryManager.RECALL_CANDIDATE_LIMIT);
        assertThat(full.truncated()).isTrue();
        assertThat(full.rankedOutCount())
                .isEqualTo(MemoryManager.RECALL_CANDIDATE_LIMIT - full.items().size());

        RecallResult empty = manager.recall(TENANT, "nobody", "记忆", null, 5);
        assertThat(empty.truncated()).isFalse();
        assertThat(empty.items()).isEmpty();
    }

    @Test
    @DisplayName("租户或用户为空即抛参数异常，不返回空结果糊弄调用方")
    void rejectsBlankScope() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_recall_blank");
        MemoryManager manager = fixture.manager();

        assertThatThrownBy(() -> manager.recall(" ", USER, QUERY, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("租户标识");
        assertThatThrownBy(() -> manager.recall(TENANT, null, QUERY, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户标识");
    }

    /** 取返回条目的分数列表（顺序敏感，用于逐位比较）。 */
    private static List<Double> scoresOf(RecallResult result) {
        return result.items().stream().map(RecallResult.Item::score).toList();
    }

    /** 按内容索引分数，便于跨参数对比同一条记忆的分数变化。 */
    private static Map<String, Double> scoreByContent(MemoryManager manager) {
        return manager.recall(TENANT, USER, QUERY, null, null).items().stream()
                .collect(Collectors.toMap(RecallResult.Item::content, RecallResult.Item::score));
    }
}
