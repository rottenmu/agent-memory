package com.zimo.module.agentmemory.governance;

import static com.zimo.module.agentmemory.governance.MemoryManagerFixture.QUERY;
import static com.zimo.module.agentmemory.governance.MemoryManagerFixture.TENANT;
import static com.zimo.module.agentmemory.governance.MemoryManagerFixture.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zimo.module.agentmemory.model.L0RawLog;
import com.zimo.module.agentmemory.model.UserMemoryRow;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 记忆治理测试：事实抽取、合并、遗忘淘汰、指标与访问计数落库。
 *
 * <p>重点验证三类容易「看起来成功」的行为：抽取被白名单/敏感策略挡下时是否可见、
 * dryRun 是否真的不落库、召回计数是否只在 flush 后写回存储（PRD §3.2
 * 「计数更新不阻塞查询」）。</p>
 */
class MemoryManagerGovernanceTest {

    /* ---------------- 抽取 ---------------- */

    @Test
    @DisplayName("从 L0 用户消息抽出候选并落库")
    void extractsAndPersists() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_extract_persist");
        fixture.repository().saveRawLog(L0RawLog.forInsert(
                "trace-x", "s-1", USER, System.currentTimeMillis(), "user",
                "以后都用 Excel 导出报表，另外我叫李雷", null, null));

        ExtractOutcome outcome =
                fixture.manager().extract(TENANT, USER, "trace-x", null, true);

        assertThat(outcome.candidates()).isPositive();
        assertThat(outcome.persisted()).isPositive();
        assertThat(outcome.persist()).isTrue();
        assertThat(outcome.items()).extracting(FactCandidate::content)
                .anyMatch(content -> content.contains("Excel"));
        // 抽出的是 persona / preference 类候选 → 落 L3 画像而非 L1 原子记忆，
        // 因此必须两表合计判断「有没有真的落库」，只看 L1 会误判为落库失败
        assertThat(fixture.repository().countAtomicByTenant(TENANT)
                + fixture.repository().countPersonaByTenant(TENANT)).isPositive();
        assertThat(fixture.runtime().extractRuns()).isEqualTo(1);
    }

    @Test
    @DisplayName("persist=false 只回显候选，不写库")
    void dryRunDoesNotPersist() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_extract_dry");
        fixture.repository().saveRawLog(L0RawLog.forInsert(
                "trace-y", "s-2", USER, System.currentTimeMillis(), "user",
                "我叫李雷，我的部门是数据平台部", null, null));

        ExtractOutcome outcome = fixture.manager().extract(TENANT, USER, null, "s-2", false);

        assertThat(outcome.sessionId()).isEqualTo("s-2");
        assertThat(outcome.candidates()).isPositive();
        assertThat(outcome.persisted()).isZero();
        assertThat(outcome.extractedNothingPersisted()).isFalse();
        assertThat(fixture.repository().countAtomicByTenant(TENANT)).isZero();
    }

    @Test
    @DisplayName("两个来源都为空时抛参数异常，而不是返回「抽到 0 条」")
    void rejectsWhenNoSource() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_extract_nosource");

        assertThatThrownBy(() -> fixture.manager().extract(TENANT, USER, null, null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traceId");
    }

    @Test
    @DisplayName("落库模式下用户为空即抛异常（否则会把记忆写到 unknown 用户名下）")
    void rejectsBlankUserWhenPersisting() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_extract_blankuser");

        assertThatThrownBy(() -> fixture.manager().extract(TENANT, " ", "trace-z", null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户标识");
    }

    /* ---------------- 合并 ---------------- */

    @Test
    @DisplayName("合并：dryRun 只产出计划不落库，真实执行后条目减少")
    void mergeRespectsDryRun() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_merge");
        fixture.service().saveUserMemory(TENANT, USER, "custom", "以后都用 Excel 导出报表");
        fixture.service().saveUserMemory(TENANT, USER, "custom", "以后都用 Excel 导出报表明细");
        MemoryManager manager = fixture.manager();

        MemoryMerger.Outcome dry = manager.merge(TENANT, USER, true);
        assertThat(dry.plan().isEmpty()).isFalse();
        assertThat(dry.deletedCount()).isZero();
        assertThat(fixture.repository().countAtomicByTenant(TENANT)).isEqualTo(2);
        assertThat(fixture.runtime().mergeRuns()).isZero();

        MemoryMerger.Outcome real = manager.merge(TENANT, USER, false);
        assertThat(real.deletedCount()).isPositive();
        assertThat(fixture.repository().countAtomicByTenant(TENANT)).isEqualTo(1);
        assertThat(fixture.runtime().mergeRuns()).isEqualTo(1);
    }

    /* ---------------- 淘汰 ---------------- */

    @Test
    @DisplayName("淘汰：受保护与分数达标分开计数，安全阀能识别比例过高")
    void evictsOnlyUnprotectedLowScoreItems() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_evict");
        fixture.service().saveUserMemory(TENANT, USER, "custom", "早已过时的临时结论");
        // 显式标 0.95 才会进入「受保护」分支：自动打分上界刻意低于保护阈值
        fixture.service().saveUserMemory(TENANT, USER, "custom", "重要偏好", 0.95);
        fixture.backdate("早已过时的临时结论", 200);
        fixture.backdate("重要偏好", 200);

        MemoryEvictor.Outcome outcome = fixture.manager().evict(TENANT, false, 0.5);

        assertThat(outcome.plan().protectedCount()).isEqualTo(1);
        assertThat(outcome.plan().evictedCount()).isEqualTo(1);
        assertThat(outcome.plan().evicted().get(0).content()).isEqualTo("早已过时的临时结论");
        assertThat(outcome.deletedCount()).isEqualTo(1);
        assertThat(outcome.suspicious()).as("1/2 远超 10% 安全阀，必须能被识别").isTrue();
        assertThat(fixture.repository().countAtomicByTenant(TENANT)).isEqualTo(1);
    }

    @Test
    @DisplayName("淘汰 dryRun 不删数据、不计指标")
    void evictDryRunKeepsData() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_evict_dry");
        fixture.service().saveUserMemory(TENANT, USER, "custom", "早已过时的临时结论");
        fixture.backdate("早已过时的临时结论", 200);

        MemoryEvictor.Outcome outcome = fixture.manager().evict(TENANT, true, 0.5);

        assertThat(outcome.deletedCount()).isZero();
        assertThat(fixture.repository().countAtomicByTenant(TENANT)).isEqualTo(1);
        assertThat(fixture.runtime().evictRuns()).isZero();
    }

    @Test
    @DisplayName("淘汰只看闲置超期条目：刚写过的记忆不进候选")
    void skipsRecentlyTouchedItems() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_evict_recent");
        fixture.service().saveUserMemory(TENANT, USER, "custom", "刚写的记忆");

        MemoryEvictor.Outcome outcome = fixture.manager().evict(TENANT, false, 0.99);

        assertThat(outcome.plan().scanned()).isZero();
        assertThat(outcome.deletedCount()).isZero();
    }

    /* ---------------- 指标与访问计数 ---------------- */

    @Test
    @DisplayName("指标来自真实存储与真实召回：无召回时不谎报命中率")
    void metricsUseRealNumbers() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_metrics");
        fixture.service().saveUserMemory(TENANT, USER, "custom", "用户记忆一");
        fixture.service().saveUserMemory(TENANT, USER, "preference", "用户记忆二");
        MemoryManager manager = fixture.manager();

        MemoryMetrics.Snapshot before = manager.metrics(TENANT);
        assertThat(before.userMemoryTotal()).isEqualTo(2);
        assertThat(before.l3Count()).isEqualTo(1);
        assertThat(before.l1Count()).isEqualTo(1);
        assertThat(before.l0Total()).isPositive();
        assertThat(before.hasRecallData()).isFalse();
        assertThat(before.recallScoreBelowAlarmLine())
                .as("没有召回数据不等于数据很差，不能一上线就告警").isFalse();
        assertThat(before.since()).isNotBlank();

        manager.recall(TENANT, USER, "记忆", null, null);
        MemoryMetrics.Snapshot after = manager.metrics(TENANT);
        assertThat(after.hasRecallData()).isTrue();
        assertThat(after.recallCalls()).isEqualTo(1);
        assertThat(after.recallHitRate()).isEqualTo(1.0);
        assertThat(after.recallAvgScore()).isPositive();
        assertThat(after.extractRuns()).isZero();
    }

    @Test
    @DisplayName("召回不写库：flush 前存储计数为 0，flush 后才写回计数与最近访问时间")
    void flushesAccessCounts() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_counter");
        fixture.seedStandard14();
        MemoryManager manager = fixture.manager();

        manager.recall(TENANT, USER, QUERY, null, null);
        List<UserMemoryRow> untouched =
                fixture.repository().pageUserMemory(TENANT, USER, null, 100, 0);
        assertThat(untouched).extracting(UserMemoryRow::accessCount).containsOnly(0);
        assertThat(manager.pendingAccessCounts()).isPositive();

        int updated = manager.flushAccessCounts();

        assertThat(updated).isPositive();
        assertThat(manager.pendingAccessCounts()).isZero();
        assertThat(fixture.repository().pageUserMemory(TENANT, USER, null, 100, 0))
                .extracting(UserMemoryRow::accessCount)
                .containsOnly(1);
    }

    @Test
    @DisplayName("重复召回同一批条目只累加一次 COUNT 语义：flush 后计数为召回次数")
    void accumulatesRepeatedRecalls() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_counter_repeat");
        fixture.seedStandard14();
        MemoryManager manager = fixture.manager();

        manager.recall(TENANT, USER, QUERY, null, null);
        manager.recall(TENANT, USER, QUERY, null, null);
        int updated = manager.flushAccessCounts();

        assertThat(updated).isEqualTo(3);
        assertThat(fixture.repository().pageUserMemory(TENANT, USER, null, 100, 0))
                .extracting(UserMemoryRow::accessCount)
                .containsOnly(2);
    }

    @Test
    @DisplayName("指标按租户统计：不成为跨租户规模探针")
    void metricsAreTenantScoped() {
        MemoryManagerFixture fixture = MemoryManagerFixture.create("mm_metrics_tenant");
        fixture.service().saveUserMemory(TENANT, USER, "custom", "租户一记忆");
        fixture.service().saveUserMemory("t2", USER, "custom", "租户二记忆");

        assertThat(fixture.manager().metrics(TENANT).l1Count()).isEqualTo(1);
        assertThat(fixture.manager().metrics("t2").l1Count()).isEqualTo(1);
    }
}
