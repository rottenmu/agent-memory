package com.zimo.module.agentmemory.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.zimo.module.agentmemory.governance.FactExtractor;
import com.zimo.module.agentmemory.governance.MemoryManager;
import com.zimo.module.agentmemory.model.MemoryPage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 计划模式经验回写测试（M4-2b，PRD §4.2 / 场景 2b）。
 *
 * <h2>为什么这组测试的关键是「驳回」用例</h2>
 * <p>正向用例（未驳回 → 回写成功）只证明「能写」。真正的风险是<b>驳回后仍然回写</b> ——
 * 那会把用户否定过的方案沉淀成长期经验。因此本类里每个正向断言都配一个
 * <b>可证伪的负向对照</b>：同一组步骤、同一租户，只改 {@code rejected} 一个变量，
 * 断言库里的条数从 1 变 0。若实现退化成「无论如何都写」，正向用例照样绿，负向用例必红。</p>
 *
 * <p>用真实 H2 仓储而非 mock：回写要验证的正是「落库了没有」，用假仓储等于自证。</p>
 */
class PlanExperienceWriterTest {

    private static final String TRACE = "tr-m4-2b";
    private static final List<String> STEPS = List.of("梳理未达账项", "核对银行流水", "人工确认金额");

    private static MemoryScope planScope() {
        return MemoryScope.of(MemoryEngineFixture.TENANT, MemoryEngineFixture.USER,
                MemoryEngineFixture.SESSION, MemoryScope.TYPE_PLAN);
    }

    /** 该租户下程序性记忆的条数（走真实仓储读路径，不用 mock）。 */
    private static int proceduralCount(MemoryEngineFixture fixture) {
        return (int) fixture.service().listUserMemory(MemoryEngineFixture.TENANT,
                MemoryEngineFixture.USER, FactExtractor.CATEGORY_PROCEDURAL, 100, 0).total();
    }

    /** 该租户下程序性记忆的正文（取第一条）。 */
    private static String firstProceduralContent(MemoryEngineFixture fixture) {
        MemoryPage page = fixture.service().listUserMemory(MemoryEngineFixture.TENANT,
                MemoryEngineFixture.USER, FactExtractor.CATEGORY_PROCEDURAL, 100, 0);
        List<Map<String, Object>> records = page.records();
        return records.isEmpty() ? null : String.valueOf(records.get(0).get("content"));
    }

    @Nested
    @DisplayName("正向：未被驳回时回写程序性记忆")
    class Writeback {

        @Test
        @DisplayName("计划完成且未驳回 → 落一条 procedural，内容含步数与步骤摘要")
        void writesProceduralWhenNotRejected() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            MemoryManager manager = fixture.manager();
            PlanExperienceWriter writer = new PlanExperienceWriter(manager);

            boolean written = writer.writeback(planScope(), false, STEPS, TRACE);

            assertThat(written).as("未驳回时应回写成功").isTrue();
            assertThat(proceduralCount(fixture)).as("应恰好落 1 条程序性记忆").isEqualTo(1);

            assertThat(firstProceduralContent(fixture))
                    .as("内容应是抽象后的方法（步数 + 步骤），不是计划全文")
                    .contains("共 3 步")
                    .contains("人工确认金额");
        }

        @Test
        @DisplayName("步骤去重：重复步骤只记一次（否则步数虚高）")
        void deduplicatesSteps() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            MemoryManager manager = fixture.manager();

            new PlanExperienceWriter(manager).writeback(planScope(), false,
                    List.of("梳理未达账项", "梳理未达账项", "核对流水"), TRACE);

            assertThat(firstProceduralContent(fixture))
                    .as("去重后应报 2 步").contains("共 2 步");
        }
    }

    @Nested
    @DisplayName("负向（判据落点）：被驳回时不回写")
    class Rejected {

        @Test
        @DisplayName("场景2b：计划被驳回 → 一条都不写（与正向用例只差 rejected 一个变量）")
        void writesNothingWhenRejected() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            MemoryManager manager = fixture.manager();
            PlanExperienceWriter writer = new PlanExperienceWriter(manager);

            boolean written = writer.writeback(planScope(), true, STEPS, TRACE);

            assertThat(written).as("被驳回时不应回写").isFalse();
            assertThat(proceduralCount(fixture))
                    .as("被驳回后库里的程序性记忆必须为 0 —— 这是标准 2b 的判据")
                    .isZero();
        }

        @Test
        @DisplayName("对照：驳回是唯一变量 —— 同一组输入改为未驳回即产生 1 条")
        void rejectionIsTheOnlyDifference() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            MemoryManager manager = fixture.manager();
            PlanExperienceWriter writer = new PlanExperienceWriter(manager);

            writer.writeback(planScope(), true, STEPS, TRACE);
            int afterRejected = proceduralCount(fixture);
            writer.writeback(planScope(), false, STEPS, TRACE);
            int afterAccepted = proceduralCount(fixture);

            assertThat(afterRejected).as("驳回后为 0").isZero();
            assertThat(afterAccepted)
                    .as("同一组步骤改为未驳回后应为 1 —— 证明差异来自驳回信号而非别处")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("边界：不写无意义的空条目、不改动非计划流程")
    class Guard {

        @Test
        @DisplayName("无步骤时不写（避免「共 0 步」这类空记忆污染召回）")
        void skipsWhenNoSteps() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            MemoryManager manager = fixture.manager();

            boolean written = new PlanExperienceWriter(manager)
                    .writeback(planScope(), false, List.of(), TRACE);

            assertThat(written).as("无步骤应返回 false").isFalse();
            assertThat(proceduralCount(fixture)).as("库里不应出现空条目").isZero();
        }

        @Test
        @DisplayName("scope 不可用（缺租户/用户）时不写：隔离键不全时写入会产生跨租户脏数据")
        void skipsWhenScopeUnusable() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            MemoryManager manager = fixture.manager();

            boolean written = new PlanExperienceWriter(manager).writeback(
                    MemoryScope.of(null, MemoryEngineFixture.USER, "s", MemoryScope.TYPE_PLAN),
                    false, STEPS, TRACE);

            assertThat(written).isFalse();
            assertThat(proceduralCount(fixture)).isZero();
        }

        @Test
        @DisplayName("回写失败不抛异常（不反噬对话）")
        void doesNotThrowOnFailure() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            MemoryManager failing = fixture.managerOn(fixture.repositoryWithFailingWrite());

            boolean written = new PlanExperienceWriter(failing)
                    .writeback(planScope(), false, STEPS, TRACE);

            assertThat(written).as("写入失败应返回 false 而不是抛错").isFalse();
        }
    }
}
