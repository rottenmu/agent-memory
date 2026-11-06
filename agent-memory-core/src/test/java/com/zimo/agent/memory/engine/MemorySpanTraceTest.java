package com.zimo.agent.memory.engine;

import java.util.ArrayList;
import java.util.List;import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 链路记忆视图验证（M3 端点 {@code GET /api/ai/memory/span/{traceId}}）。
 *
 * <p>本视图存在的理由是「异步回写不发 span」这条硬约束：只看 span 会得出「异步永远没完成」，
 * 只看审计表又看不到算法、截断这类只在属性里的信息。因此两类数据源必须合并，
 * 并由 {@code summary} + 三个判断位给出可直接断言的结论（接口层最怕「调用方自己遍历拼结论」）。</p>
 */
class MemorySpanTraceTest {

    private static final String TRACE = "trace-m3-view";

    private static MemorySpanRecord row(String phase, String operation, String status,
                                        Integer recallCount) {
        return MemorySpanRecord.forInsert(TRACE, "s1", "u1", "t1", phase, operation, "l1",
                "m1", null, null, recallCount, null, 5L, status, 1_700_000_000_000L);
    }

    private static MemorySpanRecorder.MemorySpanView view(String stepType) {
        return new MemorySpanRecorder.MemorySpanView("memory.recall", stepType, "ok", 5L,
                java.util.Map.of());
    }

    @Nested
    @DisplayName("计数汇总")
    class Summary {

        @Test
        @DisplayName("按相位与状态分别计数，pending/written 各自独立")
        void countsByPhaseAndStatus() {
            List<MemorySpanRecord> audit = List.of(
                    row(MemorySpanAttributes.PHASE_SYNC, MemorySpanAttributes.OP_RECALL,
                            MemorySpanAttributes.STATUS_OK, 2),
                    row(MemorySpanAttributes.PHASE_SYNC, MemorySpanAttributes.OP_WRITE,
                            MemorySpanAttributes.STATUS_PENDING, null),
                    row(MemorySpanAttributes.PHASE_ASYNC, MemorySpanAttributes.OP_WRITE,
                            MemorySpanAttributes.STATUS_OK, 1));

            MemorySpanTrace trace = MemorySpanTrace.of(TRACE, List.of(view("retrieval")), audit, 0);

            assertEquals(1, trace.summary().chainSpanCount());
            assertEquals(3, trace.summary().auditCount());
            assertEquals(2, trace.summary().syncCount(), "召回 + 写入骨架都产生在链路内");
            assertEquals(1, trace.summary().asyncCount());
            assertEquals(1, trace.summary().pendingCount());
            assertEquals(1, trace.summary().writtenCount());
            assertEquals(0, trace.summary().emittedAsyncCount());
        }

        @Test
        @DisplayName("异步 failed 不计入「写入完成数」（否则失败会被读成成功）")
        void failedAsyncIsNotCountedAsWritten() {
            MemorySpanTrace trace = MemorySpanTrace.of(TRACE, List.of(), List.of(
                    row(MemorySpanAttributes.PHASE_ASYNC, MemorySpanAttributes.OP_WRITE,
                            MemorySpanAttributes.STATUS_FAILED, 0)), 0);

            assertEquals(1, trace.summary().asyncCount());
            assertEquals(0, trace.summary().writtenCount());
            assertFalse(trace.asyncArrived());
        }

        @Test
        @DisplayName("dropped 丢弃行同样不算写入完成")
        void droppedAsyncIsNotCountedAsWritten() {
            MemorySpanTrace trace = MemorySpanTrace.of(TRACE, List.of(), List.of(
                    row(MemorySpanAttributes.PHASE_ASYNC, MemorySpanAttributes.OP_WRITE,
                            MemorySpanAttributes.STATUS_DROPPED, 0)), 0);

            assertEquals(1, trace.summary().asyncCount());
            assertEquals(0, trace.summary().writtenCount());
        }

        @Test
        @DisplayName("null 入参归一化为空列表，不抛 NPE")
        void toleratesNulls() {
            MemorySpanTrace trace = MemorySpanTrace.of(TRACE, null, null, 0);

            assertEquals(0, trace.summary().chainSpanCount());
            assertEquals(0, trace.summary().auditCount());
            assertTrue(trace.spans().isEmpty());
            assertTrue(trace.audit().isEmpty());
        }
    }

    @Nested
    @DisplayName("判断位（标准 8 / 标准 9）")
    class Verdicts {

        @Test
        @DisplayName("链路里有 retrieval span 时 recallSpanPresent=true（标准 8 判据）")
        void detectsRecallSpan() {
            assertTrue(MemorySpanTrace.of(TRACE, List.of(view("retrieval")), List.of(), 0)
                    .recallSpanPresent());
            assertFalse(MemorySpanTrace.of(TRACE, List.of(view("memory")), List.of(), 0)
                    .recallSpanPresent());
            assertFalse(MemorySpanTrace.of(TRACE, List.of(), List.of(), 0).recallSpanPresent(),
                    "spans 为空（例如进程重启后）不得误判为「有召回」");
        }

        @Test
        @DisplayName("sync 骨架 + async ok 结果都到齐时 asyncArrived=true（标准 9 判据）")
        void detectsAsyncArrival() {
            List<MemorySpanRecord> complete = List.of(
                    row(MemorySpanAttributes.PHASE_SYNC, MemorySpanAttributes.OP_WRITE,
                            MemorySpanAttributes.STATUS_PENDING, null),
                    row(MemorySpanAttributes.PHASE_ASYNC, MemorySpanAttributes.OP_WRITE,
                            MemorySpanAttributes.STATUS_OK, 1));
            assertTrue(MemorySpanTrace.of(TRACE, List.of(), complete, 0).asyncArrived());

            // 只有骨架：异步还没到
            assertFalse(MemorySpanTrace.of(TRACE, List.of(), List.of(complete.get(0)), 0)
                    .asyncArrived());
            // 只有异步结果：说明骨架丢了，不能算「都到齐」
            assertFalse(MemorySpanTrace.of(TRACE, List.of(), List.of(complete.get(1)), 0)
                    .asyncArrived());
        }

        @Test
        @DisplayName("违规计数为 0 时链路未被异步污染（正常恒为 false）")
        void reportsNoPollution() {
            List<MemorySpanRecord> audit = new ArrayList<>();
            audit.add(row(MemorySpanAttributes.PHASE_SYNC, MemorySpanAttributes.OP_WRITE,
                    MemorySpanAttributes.STATUS_PENDING, null));
            audit.add(row(MemorySpanAttributes.PHASE_ASYNC, MemorySpanAttributes.OP_WRITE,
                    MemorySpanAttributes.STATUS_OK, 1));

            assertFalse(MemorySpanTrace.of(TRACE, List.of(), audit, 0).chainPollutedByAsync());
        }

        @Test
        @DisplayName("违规计数 >0 时任何一条链路查询都应看到污染")
        void reportsPollutionOnceItEverHappened() {
            MemorySpanTrace trace = MemorySpanTrace.of(TRACE, List.of(), List.of(), 1);

            assertTrue(trace.chainPollutedByAsync(),
                    "异步发 span 是「绝不该发生」的路径，一旦出现过必须处处可见，"
                            + "而不是只在出事那条链路里可见");
        }
    }
}
