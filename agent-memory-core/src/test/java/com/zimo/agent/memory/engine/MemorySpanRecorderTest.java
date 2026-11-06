package com.zimo.agent.memory.engine;

import com.zimo.agent.memory.governance.RecallResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆审计记录器验证（M3 PRD §4.4 / §8.2 / §8.3）。
 *
 * <p>本类刻意覆盖三条最容易静默失效的约束：</p>
 * <ol>
 *   <li><b>异步阶段绝不允许发 span</b>（span 不导出 + 不释放，且回调被
 *       {@code catch (Exception ignored)} 包裹，坏了也不报错）；</li>
 *   <li><b>相位判据不能被业务属性污染</b> —— 曾用 {@code memory.async} 当相位判据，
 *       导致每轮正常对话都被记成违规、{@code chainPollutedByAsync} 恒为 true；</li>
 *   <li><b>属性名必须逐字正确</b> —— 写错既不会编译报错也不会运行时报错，
 *       只会让面板上那一列永远是空。</li>
 * </ol>
 */
class MemorySpanRecorderTest {

    private static final String TRACE = "trace-m3-recorder";

    private static RecallResult recall(double minScore, List<RecallResult.Item> items) {
        return new RecallResult("s1_hybrid", "报表导出", 8, minScore, 500, items.size(),
                items.size(), items);
    }

    private static RecallResult.Item item(String id, String layer, double score, int accessCount) {
        return new RecallResult.Item(id, layer, "custom", "内容 " + id, 1_700_000_000_000L, score,
                score, 1.0, 0.5, accessCount, null);
    }

    /** 指定类别的条目：M4 的 {@code memory.type} 派生口径依赖它。 */
    private static RecallResult.Item itemOfCategory(String id, String layer, String category,
                                                   double score) {
        return new RecallResult.Item(id, layer, category, "内容 " + id, 1_700_000_000_000L, score,
                score, 1.0, 0.5, 1, null);
    }

    /** 取属性并转数值：属性表是 {@code Map<String,Object>}，直接断言会把装箱差异算进来。 */
    private static double num(MemoryEngineFixture.Call span, String key) {
        return ((Number) span.attr(key)).doubleValue();
    }

    private static int intOf(MemoryEngineFixture.Call span, String key) {
        return ((Number) span.attr(key)).intValue();
    }

    /* ================= 召回 ================= */

    @Nested
    @DisplayName("召回记录")
    class Recall {

        @Test
        @DisplayName("召回走 retrieval 规范词，属性含条数/候选数/分数/命中/截断/策略/算法")
        void emitsRetrievalSpanWithAttributes() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.recorder().recordRecall(TRACE, "s1", "u1", "t1",
                    recall(0.25, List.of(item("m1", "l1", 0.8, 2), item("m2", "l1", 0.6, 7))),
                    MemorySpanAttributes.STRATEGY_PREFETCH, 12L);

            MemoryEngineFixture.Call span = fixture.sink().only();
            assertEquals("retrieval", span.stepType(), "复用 retrieval 规范词才能零改动显示在既有面板");
            assertEquals("memory.recall", span.name());
            assertEquals(MemorySpanAttributes.STATUS_OK, span.status());
            assertEquals(12L, span.latencyMs());
            assertEquals(TRACE, span.traceId());

            assertEquals(MemorySpanAttributes.OP_RECALL, span.attr(MemorySpanAttributes.OPERATION));
            assertEquals(2, intOf(span, MemorySpanAttributes.RECALL_COUNT));
            assertEquals(2, intOf(span, MemorySpanAttributes.RECALL_CANDIDATES));
            assertEquals(0.8, num(span, MemorySpanAttributes.RECALL_SCORE_TOP), 1e-9);
            assertEquals(0.7, num(span, MemorySpanAttributes.RECALL_SCORE_AVG), 1e-9);
            assertEquals(Boolean.TRUE, span.attr(MemorySpanAttributes.RECALL_HIT));
            assertEquals(Boolean.FALSE, span.attr(MemorySpanAttributes.RECALL_TRUNCATED));
            assertEquals(MemorySpanAttributes.STRATEGY_PREFETCH,
                    span.attr(MemorySpanAttributes.RECALL_STRATEGY));
            assertEquals("s1_hybrid", span.attr(MemorySpanAttributes.RECALL_ALGORITHM));
            assertEquals(7, intOf(span, MemorySpanAttributes.ACCESS_COUNT), "取命中条目的最大访问数");
            assertEquals("t1", span.attr(MemorySpanAttributes.TENANT_ID));
            assertEquals("l1", span.attr(MemorySpanAttributes.LAYER), "层取分数最高那条");
            assertEquals(Boolean.FALSE, span.attr(MemorySpanAttributes.ASYNC));
        }

        @Test
        @DisplayName("M4-1：memory.type 取命中首条（分数最高）的类别，与 layer 指向同一条")
        void derivesMemoryTypeFromTopItem() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            // 首条 procedural 是排序后的最高分；第二条类别不同，用于证明确实取了「首条」
            fixture.recorder().recordRecall(TRACE, "s1", "u1", "t1",
                    recall(0.25, List.of(
                            itemOfCategory("m1", "l1", "procedural", 0.9),
                            itemOfCategory("m2", "l1", "preference", 0.6))),
                    MemorySpanAttributes.STRATEGY_PREFETCH_PLAN, 12L);

            MemoryEngineFixture.Call span = fixture.sink().only();
            assertEquals("procedural", span.attr(MemorySpanAttributes.TYPE),
                    "类型必须落在召回 span 上，否则标准 11 无可观测判据");
            assertEquals(span.attr(MemorySpanAttributes.LAYER),
                    "l1", "层与类型取自同一条，不能各取一条");
        }

        @Test
        @DisplayName("M4-1：空召回时 memory.type 回落 custom（而非缺失，避免与「没走召回」混淆）")
        void fallsBackToCustomWhenNoItem() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.recorder().recordRecall(TRACE, "s1", "u1", "t1",
                    recall(0.25, List.of()),
                    MemorySpanAttributes.STRATEGY_PREFETCH, 5L);

            MemoryEngineFixture.Call span = fixture.sink().only();
            assertNotNull(span.attr(MemorySpanAttributes.TYPE),
                    "属性必须存在：缺失会让面板该列空白，无法与「本轮没走召回」区分");
            assertEquals("custom", span.attr(MemorySpanAttributes.TYPE));
            assertEquals(0, intOf(span, MemorySpanAttributes.RECALL_COUNT));
        }

        @Test
        @DisplayName("M4-2a：计划模式策略落 prefetch_plan，与普通预召回可区分")
        void recordsPlanStrategy() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.recorder().recordRecall(TRACE, "s1", "u1", "t1",
                    recall(0.25, List.of(itemOfCategory("m1", "l1", "procedural", 0.9))),
                    MemorySpanAttributes.STRATEGY_PREFETCH_PLAN, 8L);

            MemoryEngineFixture.Call span = fixture.sink().only();
            assertEquals("prefetch_plan", span.attr(MemorySpanAttributes.RECALL_STRATEGY),
                    "与 prefetch 共用一个取值会让标准 11 退化成人人有份");
            assertFalse(MemorySpanAttributes.STRATEGY_PREFETCH
                            .equals(span.attr(MemorySpanAttributes.RECALL_STRATEGY)),
                    "计划策略必须不等于普通预召回");
        }

        @Test
        @DisplayName("召回同时落一条 sync 审计行，字段与 span 口径一致")
        void writesSyncAuditRow() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.recorder().recordRecall(TRACE, "s1", "u1", "t1",
                    recall(0.25, List.of(item("m1", "l1", 0.8, 2), item("m2", "l1", 0.6, 7))),
                    MemorySpanAttributes.STRATEGY_PREFETCH, 12L);

            List<MemorySpanRecord> audit = fixture.audit(TRACE);
            assertEquals(1, audit.size());
            MemorySpanRecord row = audit.get(0);
            assertEquals(MemorySpanAttributes.PHASE_SYNC, row.phase());
            assertEquals(MemorySpanAttributes.OP_RECALL, row.operation());
            assertEquals("l1", row.layer());
            assertEquals("m1,m2", row.memoryIds());
            assertEquals(0.8, row.recallScore(), 1e-9);
            assertEquals(2, row.recallCount());
            assertEquals(Boolean.FALSE, row.truncated());
            assertEquals(12L, row.latencyMs());
            assertEquals(MemorySpanAttributes.STATUS_OK, row.status());
            assertEquals("t1", row.tenantId());
            assertFalse(row.async());
            assertFalse(row.pending());
            assertTrue(row.hit(), "recallCount>0 即命中");
        }

        @Test
        @DisplayName("空召回也留痕，且标记为未命中")
        void recordsEmptyRecall() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.recorder().recordRecall(TRACE, "s1", "u1", "t1", recall(0.25, List.of()),
                    MemorySpanAttributes.STRATEGY_PREFETCH, 3L);

            MemoryEngineFixture.Call span = fixture.sink().only();
            assertEquals(0, intOf(span, MemorySpanAttributes.RECALL_COUNT));
            assertEquals(Boolean.FALSE, span.attr(MemorySpanAttributes.RECALL_HIT));
            assertEquals(0.0, num(span, MemorySpanAttributes.RECALL_SCORE_AVG), 1e-9,
                    "空召回的平均分是 0，不是 NaN");
            // 没有命中条目时层回落到 l3：表达「这次没有命中任何一层」
            assertEquals(MemorySpanAttributes.LAYER_L3, span.attr(MemorySpanAttributes.LAYER));
            assertFalse(fixture.audit(TRACE).get(0).hit());
        }

        @Test
        @DisplayName("候选池被扫描上限截断时 truncated=true（缺陷 D10 的可见化）")
        void marksTruncated() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            RecallResult result = new RecallResult("s1_hybrid", "查询", 8, 0.25, 500, 500, 500,
                    List.of(item("m1", "l1", 0.8, 1)));

            fixture.recorder().recordRecall(TRACE, "s1", "u1", "t1", result,
                    MemorySpanAttributes.STRATEGY_PREFETCH, 1L);

            assertEquals(Boolean.TRUE,
                    fixture.sink().only().attr(MemorySpanAttributes.RECALL_TRUNCATED));
            assertEquals(Boolean.TRUE, fixture.audit(TRACE).get(0).truncated());
        }

        @Test
        @DisplayName("记忆 ID 超过 20 个时截断并保留总数")
        void truncatesLongIdList() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            List<RecallResult.Item> items = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                items.add(item("m" + i, "l1", 0.5, 1));
            }
            fixture.recorder().recordRecall(TRACE, "s1", "u1", "t1", recall(0.25, items),
                    MemorySpanAttributes.STRATEGY_PREFETCH, 1L);

            String ids = fixture.audit(TRACE).get(0).memoryIds();
            assertNotNull(ids);
            assertTrue(ids.endsWith(",...(25)"), "必须保留「共有多少个」的信息：" + ids);
            assertEquals(20, ids.substring(0, ids.indexOf(",...(")).split(",").length);
        }

        @Test
        @DisplayName("result 为 null 时完全不做任何事")
        void ignoresNullResult() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.recorder().recordRecall(TRACE, "s1", "u1", "t1", null,
                    MemorySpanAttributes.STRATEGY_PREFETCH, 1L);

            assertEquals(0, fixture.sink().size());
            assertTrue(fixture.audit(TRACE).isEmpty());
            assertEquals(0, fixture.recorder().emittedCount());
        }
    }

    /* ================= 同步骨架 ================= */

    @Nested
    @DisplayName("写入骨架")
    class WritePending {

        @Test
        @DisplayName("骨架发在同步阶段，但业务属性 async=true（描述的是即将异步完成的写入）")
        void emitsSyncSpanMarkedAsync() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.recorder().recordWritePending(TRACE, "s1", "u1", "t1", 3, 5L);

            MemoryEngineFixture.Call span = fixture.sink().only();
            assertEquals("memory", span.stepType());
            assertEquals("memory.write", span.name());
            assertEquals(MemorySpanAttributes.STATUS_PENDING, span.status());
            assertEquals(Boolean.TRUE, span.attr(MemorySpanAttributes.ASYNC));
            assertEquals(MemorySpanAttributes.OP_WRITE, span.attr(MemorySpanAttributes.OPERATION));
            assertEquals("t1", span.attr(MemorySpanAttributes.TENANT_ID));
        }

        @Test
        @DisplayName("骨架不会被算成「链路收尾后发 span」的违规（相位判据不得用业务属性）")
        void doesNotTripAsyncViolationCounter() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.recorder().recordWritePending(TRACE, "s1", "u1", "t1", 1, 5L);

            assertEquals(0, fixture.recorder().emittedAsyncCount(),
                    "业务属性 async=true 只表示「这次写入是异步完成的」；"
                            + "把它当相位判据会让每轮正常对话都被记成违规，"
                            + "并让 /span 的 chainPollutedByAsync 恒为 true");
        }

        @Test
        @DisplayName("骨架落 sync + pending 审计行，供与异步结果按 traceId 对齐")
        void writesPendingAuditRow() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.recorder().recordWritePending(TRACE, "s1", "u1", "t1", 2, 4L);

            MemorySpanRecord row = fixture.audit(TRACE).get(0);
            assertEquals(MemorySpanAttributes.PHASE_SYNC, row.phase());
            assertEquals(MemorySpanAttributes.OP_WRITE, row.operation());
            assertEquals(MemorySpanAttributes.STATUS_PENDING, row.status());
            assertEquals(MemorySpanAttributes.LAYER_L0, row.layer());
            assertTrue(row.pending());
            assertFalse(row.async());
        }
    }

    /* ================= 异步结果 ================= */

    @Nested
    @DisplayName("异步回写结果")
    class AsyncWriteback {

        @Test
        @DisplayName("异步结果只写审计表，绝不发 span")
        void writesAuditOnlyAndNeverEmitsSpan() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.recorder().recordAsyncWriteback("t1", "u1", TRACE, "s1", 2,
                    List.of("l1-a", "l1-b"), 30L, MemorySpanAttributes.STATUS_OK);

            assertEquals(0, fixture.sink().size(),
                    "链路已收尾，此刻发 span 会「不导出 + 不释放」");
            assertEquals(0, fixture.recorder().emittedAsyncCount(), "违规计数必须恒为 0");

            MemorySpanRecord row = fixture.audit(TRACE).get(0);
            assertEquals(MemorySpanAttributes.PHASE_ASYNC, row.phase());
            assertEquals(MemorySpanAttributes.OP_WRITE, row.operation());
            assertEquals(MemorySpanAttributes.LAYER_L1, row.layer());
            assertEquals(2, row.recallCount(), "写入动作下该列语义是「新落库事实数」");
            assertEquals("l1-a,l1-b", row.memoryIds());
            assertEquals(MemorySpanAttributes.STATUS_OK, row.status());
            assertTrue(row.async());
            assertFalse(row.pending());
        }

        @Test
        @DisplayName("异步失败也留痕（status=failed），不允许静默")
        void recordsFailure() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.recorder().recordAsyncWriteback("t1", "u1", TRACE, "s1", 0, null, 7L,
                    MemorySpanAttributes.STATUS_FAILED);

            MemorySpanRecord row = fixture.audit(TRACE).get(0);
            assertEquals(MemorySpanAttributes.STATUS_FAILED, row.status());
            assertNull(row.memoryIds(), "无落库 ID 时列值为 null，而不是空串");
            assertFalse(row.hit());
        }

        @Test
        @DisplayName("正常链路走完「召回 → 骨架 → 异步结果」后违规计数仍为 0（标准 9 前提）")
        void fullTurnKeepsViolationCounterAtZero() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.recorder().recordRecall(TRACE, "s1", "u1", "t1",
                    recall(0.25, List.of(item("m1", "l1", 0.8, 1))),
                    MemorySpanAttributes.STRATEGY_PREFETCH, 5L);
            fixture.recorder().recordWritePending(TRACE, "s1", "u1", "t1", 1, 2L);
            fixture.recorder().recordAsyncWriteback("t1", "u1", TRACE, "s1", 1,
                    List.of("m2"), 20L, MemorySpanAttributes.STATUS_OK);

            assertEquals(0, fixture.recorder().emittedAsyncCount());
            assertEquals(2, fixture.sink().size(), "链路内只有召回与骨架两个 span");
            assertEquals(3, fixture.audit(TRACE).size(), "审计有三行：召回 / 骨架 / 异步结果");
        }
    }

    /* ================= 无链路动作 ================= */

    @Nested
    @DisplayName("无活跃链路的动作")
    class Detached {

        /**
         * 审计表<b>刻意不提供「全表查」</b>（它会退化成数据导出，且按 traceId 反查是唯一
         * 真实读路径），因此无链路记录只能靠捕获写入来断言 —— 这本身就是被测行为的一部分。
         */
        @Test
        @DisplayName("定时淘汰等动作不伪造 traceId，只落审计行")
        void doesNotFakeTraceId() {
            MemoryEngineFixture.CapturingAuditRepository repo =
                    new MemoryEngineFixture.CapturingAuditRepository();
            MemorySpanRecorder recorder = new MemorySpanRecorder(repo, MemoryTraceSink.NOOP);

            recorder.recordDetached(MemorySpanAttributes.OP_EVICT, "t1",
                    MemorySpanAttributes.LAYER_L1, List.of("m1", "m2"), 8L,
                    MemorySpanAttributes.STATUS_OK);

            assertEquals(1, repo.rows.size());
            MemorySpanRecord row = repo.rows.get(0);
            assertNull(row.traceId(), "硬塞一个伪造 ID 会让「按 traceId 反查」这条路径失效");
            assertEquals(MemorySpanAttributes.OP_EVICT, row.operation());
            assertEquals("m1,m2", row.memoryIds());
            assertEquals(2, row.recallCount());
            assertEquals(MemorySpanAttributes.PHASE_ASYNC, row.phase());
            assertEquals("t1", row.tenantId());
        }

        @Test
        @DisplayName("无链路记录按 traceId 查不到（拒绝全表导出）")
        void detachedRowsAreNotQueryableByTrace() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.recorder().recordDetached(MemorySpanAttributes.OP_EVICT, "t1",
                    MemorySpanAttributes.LAYER_L1, null, 1L, MemorySpanAttributes.STATUS_OK);

            assertTrue(fixture.audit(null).isEmpty(), "traceId 为空时必须返回空列表而不是全表");
        }
    }

    /* ================= 留存与容错 ================= */

    @Nested
    @DisplayName("留存与容错")
    class RetainAndTolerate {

        @Test
        @DisplayName("spansOf 返回本模块发出的 span 副本，未知/空 traceId 返回空列表")
        void returnsRetainedSpans() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.recorder().recordWritePending(TRACE, "s1", "u1", "t1", 0, 1L);

            assertEquals(1, fixture.recorder().spansOf(TRACE).size());
            assertEquals("memory.write", fixture.recorder().spansOf(TRACE).get(0).name());
            assertTrue(fixture.recorder().spansOf("trace-不存在").isEmpty());
            assertTrue(fixture.recorder().spansOf(null).isEmpty());
        }

        @Test
        @DisplayName("留存属性是只读副本，外部改不动（否则面板查询会与埋点互相污染）")
        void keepsReadOnlyAttributeCopy() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.recorder().recordWritePending(TRACE, "s1", "u1", "t1", 0, 1L);
            var attributes = fixture.recorder().spansOf(TRACE).get(0).attributes();

            assertThrows(UnsupportedOperationException.class, () -> attributes.put("x", "y"));
            assertFalse(attributes.containsKey("x"));
        }

        @Test
        @DisplayName("埋点失败不影响审计与留存（失败不反噬，但不静默）")
        void sinkFailureDoesNotPropagate() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            fixture.sink().throwOnStep = true;

            assertDoesNotThrow(() -> fixture.recorder().recordWritePending(
                    TRACE, "s1", "u1", "t1", 0, 1L));

            assertEquals(1, fixture.recorder().emittedCount(), "发出的 span 数照常计数");
            assertEquals(1, fixture.audit(TRACE).size(), "审计必须已经写成功");
            assertEquals(1, fixture.recorder().spansOf(TRACE).size(), "留存副本仍应保留");
        }

        @Test
        @DisplayName("审计写入失败不影响埋点与业务")
        void auditFailureDoesNotPropagate() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            MemoryEngineFixture.CapturingAuditRepository repo =
                    new MemoryEngineFixture.CapturingAuditRepository();
            repo.throwOnSave = true;
            MemorySpanRecorder recorder = new MemorySpanRecorder(repo, fixture.sink());

            assertDoesNotThrow(() -> recorder.recordWritePending(TRACE, "s1", "u1", "t1", 0, 1L));

            assertEquals(1, fixture.sink().size(), "审计挂了也必须把 span 发出去");
            assertEquals(1, recorder.spansOf(TRACE).size());
        }
    }
}
