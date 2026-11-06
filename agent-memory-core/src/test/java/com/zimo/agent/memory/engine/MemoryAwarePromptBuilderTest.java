package com.zimo.agent.memory.engine;

import com.zimo.agent.memory.governance.FactExtractor;
import com.zimo.agent.memory.governance.RecallResult;
import com.zimo.agent.memory.model.L0RawLog;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆感知增强器（M3 预召回 + 片段组装 + 留痕 + 回写投递）验证。
 *
 * <p>覆盖 PRD 验收标准 1（记忆能被注入）、8（链路里出现召回 span）、
 * 13（注入内容进 L0）与 R3（冲突声明不可省略）。</p>
 *
 * <p><b>本类只验证「片段从哪来、长什么样、留没留痕」</b>：片段<b>放到哪里</b>
 * （系统提示词）由 {@code framework-ai} 的 {@code MemoryPromptMiddleware} 决定，
 * 那个契约在 {@code MemoryPromptMiddlewareTest} 里验证。早期版本由本类自己往
 * messages 里塞 SYSTEM 消息，真机被 AgentScope 拒绝，因此这个职责被移走了。</p>
 *
 * <p>本类刻意<b>不</b>用 mock 框架：召回 → 阈值过滤 → 组装 → 留痕是一条链，
 * 逐段替换成假实现会让测试全绿而线上仍可能错。只有「故意制造失败」的用例才用最小桩，
 * 且桩只替换<b>存储</b>这一层。</p>
 */
class MemoryAwarePromptBuilderTest {

    private static final String TRACE = "trace-m3-prompt";

    /** 构造增强器；不需要回写队列时传 null。 */
    private MemoryAwarePromptBuilder builder(MemoryEngineFixture fixture,
                                             MemoryWritebackQueue queue, boolean enabled) {
        return new MemoryAwarePromptBuilder(fixture.manager(), fixture.recorder(), queue,
                fixture.repository(), enabled);
    }

    /** 手工构造召回结果（用于纯函数断言，不受打分口径影响）。 */
    private static RecallResult recall(double minScore, List<RecallResult.Item> items) {
        return new RecallResult("s1_hybrid", "报表导出", 8, minScore, 500, items.size(),
                items.size(), items);
    }

    private static RecallResult.Item item(String id, String category, String content, double score) {
        return new RecallResult.Item(id, "l1", category, content, 1_700_000_000_000L, score,
                score, 1.0, 0.5, 3, null);
    }

    /**
     * 造一条「总分与内容相关性可以分开设定」的条目。
     *
     * <p>用于验证注入判据的两个维度：{@code match = 0} 但总分够高时不得注入
     * （recency/importance 单独就能把分数顶到阈值之上）。</p>
     */
    private static RecallResult.Item noiseItem(double score, double match) {
        return new RecallResult.Item("m-noise", "l1", "custom", "公司食堂中午排队很久",
                1_700_000_000_000L, score, match, 1.0, 0.5, 0, null);
    }

    /** 取整型属性：属性表是 {@code Map<String,Object>}，直接断言会把装箱差异算进来。 */
    private static int intOf(MemoryEngineFixture.Call span, String key) {
        return ((Number) span.attr(key)).intValue();
    }

    /* ================= 片段组装（纯函数） ================= */

    @Nested
    @DisplayName("片段组装")
    class Compose {

        @Test
        @DisplayName("低于注入阈值的条目被剔除，只保留达标项")
        void filtersBelowThreshold() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            RecallResult result = recall(0.25, List.of(
                    item("m1", "custom", "以后都用 Excel 导出报表", 0.90),
                    item("m2", "custom", "公司食堂中午排队很久", 0.10)));

            String fragment = newBuilder(fixture).composeFragment(result);

            assertNotNull(fragment);
            assertTrue(fragment.contains("以后都用 Excel 导出报表"));
            assertFalse(fragment.contains("公司食堂中午排队很久"),
                    "低于注入阈值的条目不得出现在片段里");
        }

        @Test
        @DisplayName("条目格式为「- [类别|分数] 内容」，分数固定两位小数")
        void formatsItemLine() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            String fragment = newBuilder(fixture).composeFragment(
                    recall(0.25, List.of(item("m1", "custom", "用 Excel 导出", 0.5))));

            assertTrue(fragment.contains("- [custom|0.50] 用 Excel 导出"),
                    "实际片段：" + fragment);
        }

        @Test
        @DisplayName("首行声明「冲突以用户当前陈述为准」（R3 的唯一防线）")
        void declaresConflictPrecedence() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            String fragment = newBuilder(fixture).composeFragment(
                    recall(0.25, List.of(item("m1", "custom", "偏好邮件汇报", 0.8))));

            assertNotNull(fragment);
            assertTrue(fragment.startsWith(MemoryAwarePromptBuilder.FRAGMENT_HEADER),
                    "片段必须以标题行开头，实际：" + fragment);
            assertTrue(fragment.contains("以用户当前陈述为准"),
                    "冲突声明缺失会让用户改了偏好却永远被旧偏好处理（R3）");
        }

        @Test
        @DisplayName("条目内容里的换行被压成空格，不破坏片段的行结构")
        void flattensNewlines() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            String fragment = newBuilder(fixture).composeFragment(
                    recall(0.25, List.of(item("m1", "custom", "第一行\n第二行", 0.8))));

            assertNotNull(fragment);
            assertEquals(2, fragment.lines().count(), "标题行 + 一条条目行，共两行");
            assertTrue(fragment.contains("第一行 第二行"));
        }

        @Test
        @DisplayName("无条目达到阈值时返回 null（调用方据此跳过注入）")
        void returnsNullWhenNothingQualifies() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            assertNull(newBuilder(fixture).composeFragment(
                    recall(0.9, List.of(item("m1", "custom", "低分内容", 0.1)))),
                    "无条目达标时必须返回 null，否则调用方会注入一个只有标题的空片段");
        }

        @Test
        @DisplayName("空召回返回 null，且不产生 NPE")
        void returnsNullOnEmptyItems() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            assertNull(newBuilder(fixture).composeFragment(recall(0.25, List.of())));
        }

        @Test
        @DisplayName("总分达标但毫无内容相关性时不注入（阈值不能被 recency/importance 单独满足）")
        void ignoresHighScoreWithoutRelevance() {
            // 真机复现（2026-09-16）：写 2 条「报表导出用 Excel」，再用完全不相关的话发起对话，
            // 召回仍返回 2 条、score.top=0.4 —— match=0、decay=1.0、importance=0.5，
            // 即 0.3·1.0 + 0.2·0.5 = 0.4 > 阈值 0.25，按旧判据会被全部注入。
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            assertNull(newBuilder(fixture).composeFragment(
                            recall(0.25, List.of(noiseItem(0.4, 0.0)))),
                    "只看总分会让「够新」的无关记忆每轮都被注入，正是 R3 要防的失败模式");
        }

        @Test
        @DisplayName("有相关性且总分达标时照常注入（收紧判据不得误伤真命中）")
        void stillInjectsWhenRelevant() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            String fragment = newBuilder(fixture).composeFragment(
                    recall(0.25, List.of(noiseItem(0.4, 0.3))));

            assertNotNull(fragment, "相关性 > 0 且分数达标时必须注入，收紧判据不能把真命中一起挡掉");
        }

        @Test
        @DisplayName("内容为 null 的条目按空串处理，不抛 NPE")
        void toleratesNullContent() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            String fragment = newBuilder(fixture).composeFragment(
                    recall(0.25, List.of(item("m1", "custom", null, 0.8))));

            assertNotNull(fragment);
            assertTrue(fragment.contains("- [custom|0.80]"));
        }

        private MemoryAwarePromptBuilder newBuilder(MemoryEngineFixture fixture) {
            return builder(fixture, null, true);
        }
    }

    /* ================= 预召回主链路 ================= */

    @Nested
    @DisplayName("预召回")
    class Recall {

        @Test
        @DisplayName("命中记忆时返回完整片段（标题 + 条目，且含命中内容）")
        void returnsFragmentOnHit() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.seed(MemoryEngineFixture.HIT_CONTENT);

            String fragment = builder(fixture, null, true)
                    .recallFragment(scope(), MemoryEngineFixture.QUERY, TRACE);

            assertNotNull(fragment, "写进去又能召回的内容必须产出片段，否则标准 1 不成立");
            assertTrue(fragment.startsWith(MemoryAwarePromptBuilder.FRAGMENT_HEADER));
            assertTrue(fragment.contains(MemoryEngineFixture.HIT_CONTENT));
        }

        @Test
        @DisplayName("空召回返回 null，连标题都不给（避免污染上下文与破坏缓存命中）")
        void returnsNullOnEmptyRecall() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();

            assertNull(builder(fixture, null, true)
                            .recallFragment(scope(), MemoryEngineFixture.QUERY, TRACE),
                    "不得返回「暂无记忆」之类的占位文本");
        }

        @Test
        @DisplayName("全部低于注入阈值时返回 null，但召回本身仍被记账")
        void returnsNullWhenBelowThreshold() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.seed(MemoryEngineFixture.NOISE_CONTENT);
            // 阈值 0.99：召回仍会返回条目，但一条都不该被注入
            MemoryAwarePromptBuilder builder = new MemoryAwarePromptBuilder(
                    fixture.manager(0.99), fixture.recorder(), null, fixture.repository(), true);

            String fragment = builder.recallFragment(scope(), MemoryEngineFixture.QUERY, TRACE);

            assertNull(fragment, "无一条达标时不注入");
            // 阈值只影响「注入」，不影响「召回」：召回 span 与审计都要留下，
            // 否则调试时无法区分「没召回」与「召回了但一条都没达标」
            assertEquals("memory." + MemorySpanAttributes.OP_RECALL, fixture.sink().only().name());
            assertEquals(1, fixture.audit(TRACE).size(), "召回动作必须留痕，即使什么都没注入");
        }

        @Test
        @DisplayName("关闭开关时既不召回也不埋点")
        void doesNothingWhenDisabled() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.seed(MemoryEngineFixture.HIT_CONTENT);

            assertNull(builder(fixture, null, false)
                    .recallFragment(scope(), MemoryEngineFixture.QUERY, TRACE));
            assertEquals(0, fixture.sink().size(), "关闭时不应有任何埋点");
            assertTrue(fixture.audit(TRACE).isEmpty(), "关闭时不应写审计");
        }

        @Test
        @DisplayName("隔离维度不完整（缺租户）时不召回")
        void skipsWhenScopeUnusable() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();

            assertNull(builder(fixture, null, true).recallFragment(
                    MemoryScope.of("  ", MemoryEngineFixture.USER, "s1"),
                    MemoryEngineFixture.QUERY, TRACE));
            assertEquals(0, fixture.sink().size());
        }

        @Test
        @DisplayName("隔离维度为 null 时不召回")
        void skipsWhenScopeNull() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();

            assertNull(builder(fixture, null, true)
                    .recallFragment(null, MemoryEngineFixture.QUERY, TRACE));
            assertEquals(0, fixture.sink().size());
        }

        @Test
        @DisplayName("traceId 为空时不召回（不埋无主的点）")
        void skipsWhenTraceIdMissing() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.seed(MemoryEngineFixture.HIT_CONTENT);

            assertNull(builder(fixture, null, true)
                    .recallFragment(scope(), MemoryEngineFixture.QUERY, null));
            assertTrue(fixture.audit(TRACE).isEmpty());
        }

        @Test
        @DisplayName("召回抛异常时返回 null，不阻断对话")
        void recallFailureDoesNotBreakTurn() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            // 故障点放在存储层（拉候选池失败），上层代码路径全部真实
            MemoryAwarePromptBuilder builder = new MemoryAwarePromptBuilder(
                    fixture.managerOn(fixture.repositoryWithFailingPage()), fixture.recorder(),
                    null, fixture.repository(), true);

            assertNull(builder.recallFragment(scope(), MemoryEngineFixture.QUERY, TRACE),
                    "召回失败时本轮应退化为「没有记忆可用」，而不是让对话失败");
            assertEquals(0, fixture.sink().size(), "召回都没成功，不应发出召回 span");
        }
    }

    /* ================= 注入留痕（标准 13） ================= */

    @Nested
    @DisplayName("注入留痕")
    class InjectionAudit {

        @Test
        @DisplayName("注入内容被写入 L0，source 标记为 context_injection")
        void writesFragmentIntoL0() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.seed(MemoryEngineFixture.HIT_CONTENT);

            builder(fixture, null, true)
                    .recallFragment(scope(), MemoryEngineFixture.QUERY, TRACE);

            List<L0RawLog> logs = fixture.rawLogs(TRACE);
            assertEquals(1, logs.size(), "注入应产生恰好一条 L0 记录");
            L0RawLog injection = logs.get(0);
            assertEquals(MemoryAwarePromptBuilder.SOURCE_CONTEXT_INJECTION, injection.source());
            assertEquals("system", injection.role());
            assertTrue(injection.content().startsWith(MemoryAwarePromptBuilder.FRAGMENT_HEADER),
                    "留痕必须是模型实际看到的原文，不含裁剪");
            assertNotNull(injection.metaJson(), "留痕元数据应记录当时的判定边界");
            assertTrue(injection.metaJson().contains("\"injected\":1"), injection.metaJson());
        }

        @Test
        @DisplayName("未注入时不产生任何 L0 记录（标准 13）")
        void writesNothingWhenNotInjected() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();

            builder(fixture, null, true)
                    .recallFragment(scope(), MemoryEngineFixture.QUERY, TRACE);

            assertTrue(fixture.rawLogs(TRACE).isEmpty(),
                    "新用户没有任何记忆时，L0 里不该出现「记忆」标题行");
        }

        @Test
        @DisplayName("留痕写入失败不影响片段产出（增强能力不反噬主流程）")
        void l0FailureDoesNotBreakRecall() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.seed(MemoryEngineFixture.HIT_CONTENT);
            MemoryAwarePromptBuilder builder = new MemoryAwarePromptBuilder(
                    fixture.manager(), fixture.recorder(), null,
                    fixture.repositoryWithFailingRawLog(), true);

            String fragment = builder.recallFragment(scope(), MemoryEngineFixture.QUERY, TRACE);

            assertNotNull(fragment, "留痕是旁路动作，失败不得导致本轮记忆丢失");
            assertTrue(fragment.contains(MemoryEngineFixture.HIT_CONTENT));
        }
    }

    /* ================= 回复后异步回写 ================= */

    @Nested
    @DisplayName("回复后异步回写")
    class AfterReply {

        @Test
        @DisplayName("提交回写任务并留下 pending 骨架 span（异步结果由审计表承载）")
        void submitsAndRecordsPending() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            try (MemoryWritebackQueue queue =
                         new MemoryWritebackQueue(fixture.manager(), fixture.recorder())) {

                builder(fixture, queue, true).afterReply(
                        scope(), TRACE, "以后都用 Excel 导出报表", "好的，记住了");

                List<MemorySpanRecord> audit = fixture.audit(TRACE);
                assertEquals(1, audit.size());
                assertEquals(MemorySpanAttributes.PHASE_SYNC, audit.get(0).phase());
                assertEquals(MemorySpanAttributes.OP_WRITE, audit.get(0).operation());
                assertEquals(MemorySpanAttributes.STATUS_PENDING, audit.get(0).status());
                assertTrue(audit.get(0).pending());

                MemoryEngineFixture.Call span = fixture.sink().only();
                assertEquals("memory", span.stepType());
                assertEquals(MemorySpanAttributes.STATUS_PENDING, span.status());
                assertEquals(Boolean.TRUE, span.attr(MemorySpanAttributes.ASYNC),
                        "骨架描述的是异步写入，因此业务属性 async=true");
            }
        }

        @Test
        @DisplayName("本轮无有效文本时不提交任务")
        void skipsWhenNoText() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            try (MemoryWritebackQueue queue =
                         new MemoryWritebackQueue(fixture.manager(), fixture.recorder())) {

                builder(fixture, queue, true).afterReply(scope(), TRACE, "   ", null);

                assertTrue(fixture.audit(TRACE).isEmpty());
                assertEquals(0, queue.depth());
            }
        }

        @Test
        @DisplayName("关闭开关时不提交任务")
        void skipsWhenDisabled() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            try (MemoryWritebackQueue queue =
                         new MemoryWritebackQueue(fixture.manager(), fixture.recorder())) {

                builder(fixture, queue, false).afterReply(scope(), TRACE, "用户消息", "回复");

                assertTrue(fixture.audit(TRACE).isEmpty());
                assertEquals(0, queue.depth());
            }
        }
    }

    private static MemoryScope scope() {
        return MemoryScope.of(MemoryEngineFixture.TENANT, MemoryEngineFixture.USER,
                MemoryEngineFixture.SESSION);
    }

    /** 指定智能体类型的 scope（M4-2a）。 */
    private static MemoryScope scopeOf(String agentType) {
        return MemoryScope.of(MemoryEngineFixture.TENANT, MemoryEngineFixture.USER,
                MemoryEngineFixture.SESSION, agentType);
    }

    /* ================= 计划模式分流（M4-2a，标准 11） ================= */

    /**
     * 标准 11 的正向与隔离两条路径。
     *
     * <p><b>为什么要成对断言</b>：只测「plan 模式召回了程序性记忆」是单向的 ——
     * 若实现把「所有模式都放开」当成修复，正向照样通过，隔离维度却已失效。
     * 因此每个正向用例都必须配一条「非 plan 模式拿不到」的对照。</p>
     */
    @Nested
    @DisplayName("计划模式分流")
    class PlanMode {

        @Test
        @DisplayName("Standard11 正向：plan 模式召回程序性记忆并注入")
        void planModeRecallsProcedural() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.seedOf("对账任务通常需要 5 步，第 3 步要人工确认",
                    FactExtractor.CATEGORY_PROCEDURAL);

            String fragment = builder(fixture, null, true)
                    .recallFragment(scopeOf("plan"), "对账任务怎么做", TRACE);

            assertNotNull(fragment, "plan 模式下程序性记忆必须能被召回并注入");
            assertTrue(fragment.contains("对账任务"), "注入片段应含程序性记忆内容");
            assertEquals("prefetch_plan",
                    fixture.sink().only().attr(MemorySpanAttributes.RECALL_STRATEGY),
                    "策略标记必须区别于普通预召回");
            assertEquals(FactExtractor.CATEGORY_PROCEDURAL,
                    fixture.sink().only().attr(MemorySpanAttributes.TYPE),
                    "标准 11 的判据：memory.type=procedural 出现在召回 span 上");
        }

        @Test
        @DisplayName("Standard11 隔离：conversation 模式召回不到同一条程序性记忆")
        void conversationModeDoesNotRecallProcedural() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.seedOf("对账任务通常需要 5 步，第 3 步要人工确认",
                    FactExtractor.CATEGORY_PROCEDURAL);

            String fragment = builder(fixture, null, true)
                    .recallFragment(scopeOf("conversation"), "对账任务怎么做", TRACE);

            // 这条用例在真机/单测里都抓到过缺陷：最初只实现「plan 模式加白名单」，
            // 非 plan 传 null 表示「不限类别」，于是程序性记忆照样被注入。
            // 「没加白名单」不等于「被排除」——必须有显式的黑名单。
            assertNull(fragment,
                    "非计划模式不得注入程序性记忆，否则标准 11 的隔离维度当场失效");
            assertEquals(0, intOf(fixture.sink().only(), MemorySpanAttributes.RECALL_COUNT),
                    "不是「召回了但没注入」——类别黑名单应让它在召回阶段就不出现");
        }

        @Test
        @DisplayName("Standard11 隔离：conversation 模式下其他类别记忆仍照常注入（不是一刀切封禁）")
        void conversationModeStillRecallsOrdinaryCategories() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.seed(MemoryEngineFixture.HIT_CONTENT);

            String fragment = builder(fixture, null, true)
                    .recallFragment(scopeOf("conversation"), MemoryEngineFixture.QUERY, TRACE);

            assertNotNull(fragment,
                    "黑名单只能排除 procedural，不能连普通类别一起挡掉 —— "
                            + "否则是「为通过隔离用例而砍掉正常功能」");
            assertTrue(fragment.contains(MemoryEngineFixture.HIT_CONTENT));
        }

        @Test
        @DisplayName("Standard11 隔离：未指定类型时行为与 M4 之前完全一致（不限类别）")
        void nullAgentTypeKeepsLegacyBehaviour() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.seed(MemoryEngineFixture.HIT_CONTENT);

            String fragment = builder(fixture, null, true)
                    .recallFragment(scope(), MemoryEngineFixture.QUERY, TRACE);

            assertNotNull(fragment, "存量调用点（agentType=null）不得因 M4 而改变行为");
            assertEquals(MemorySpanAttributes.STRATEGY_PREFETCH,
                    fixture.sink().only().attr(MemorySpanAttributes.RECALL_STRATEGY),
                    "无类型时仍记普通预召回，不能误判成计划模式");
        }

        @Test
        @DisplayName("Standard11：plan 模式下普通类别不参与召回（已知取舍，固化当前语义）")
        void planModeOnlyRecallsProcedural() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.seed(MemoryEngineFixture.HIT_CONTENT);

            String fragment = builder(fixture, null, true)
                .recallFragment(scopeOf("plan"), MemoryEngineFixture.QUERY, TRACE);

            // 这是**有意的当前取舍**，不是「正确」的终态：plan 模式只拉 procedural，
            // 因此普通类别记忆在计划模式下一律缺席。记录在此是为了让这个取舍可见 ——
            // 若将来改为「procedural ∪ 全类别」，此处应同步改为 assertNotNull，
            // 而不是让实现悄悄改变行为却没人注意到。
            assertNull(fragment, "当前语义：plan 模式只召回程序性记忆");
        }
    }
}
