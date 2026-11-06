package com.zimo.agent.memory.engine;

import com.zimo.agent.memory.model.L1AtomicMemory;
import com.zimo.agent.memory.model.L3Persona;
import com.zimo.agent.memory.storage.FakeOltpMemoryRepository;
import com.zimo.agent.memory.model.L0RawLog;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事实抽取异步回写队列验证（M3 PRD §7.2 / §9.2）。
 *
 * <p>核心要证明两件事：</p>
 * <ol>
 *   <li><b>异步结果不回头污染已收尾的链路</b> —— 只落审计表，一条 span 都不发
 *       （发出去会「不导出 + 不释放」，且被 {@code catch (Exception ignored)} 吞掉）；</li>
 *   <li><b>背压与失败都必须留痕</b> —— 丢弃要留 {@code dropped}、抽取异常要留
 *       {@code failed}，静默丢数据是本项目反复出现的问题类型。</li>
 * </ol>
 */
class MemoryWritebackQueueTest {

    private static final String TRACE = "trace-m3-writeback";

    /** 构造一轮对话的两条 L0 记录（队列任务自带消息体，异步侧不再回查 L0）。 */
    private static List<L0RawLog> turn(String userMessage, String reply) {
        long ts = System.currentTimeMillis();
        return List.of(
                L0RawLog.forInsert(TRACE, "s1", "u1", ts, "user", userMessage, null, null),
                L0RawLog.forInsert(TRACE, "s1", "u1", ts, "assistant", reply, null, null));
    }

    /** 轮询等待条件成立（避免用固定 sleep 造成不稳定）。 */
    private static void await(String what, java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待被中断：" + what, e);
            }
        }
        throw new AssertionError("等待超时：" + what);
    }

    @Nested
    @DisplayName("正常回写")
    class Normal {

        @Test
        @DisplayName("任务被消费后写 async 审计行，且一条 span 都不发")
        void consumesTaskAndWritesAsyncAudit() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            try (MemoryWritebackQueue queue =
                         new MemoryWritebackQueue(fixture.manager(), fixture.recorder())) {

                queue.submit(new MemoryWritebackQueue.Task("t1", "u1", TRACE, "s1",
                        turn("以后都用 Excel 导出报表", "好的，记住了")));
                await("队列消费完成", () -> queue.consumedCount() == 1);
                await("审计落库", () -> !fixture.audit(TRACE).isEmpty());

                assertEquals(0, queue.failedCount());
                assertEquals(0, queue.droppedCount());
                assertEquals(0, fixture.sink().size(),
                        "异步阶段一律不发 span：此刻链路已收尾，发了既不导出也不释放");
                assertEquals(0, fixture.recorder().emittedAsyncCount());

                MemorySpanRecord row = fixture.audit(TRACE).get(0);
                assertEquals(MemorySpanAttributes.PHASE_ASYNC, row.phase());
                assertEquals(MemorySpanAttributes.OP_WRITE, row.operation());
                assertEquals(MemorySpanAttributes.LAYER_L1, row.layer());
                assertEquals(MemorySpanAttributes.STATUS_OK, row.status());
                assertNotNull(row.latencyMs(), "异步耗时必须记录，否则无法判断抽取是否变慢");
                assertEquals(1, row.recallCount(),
                        "「以后都用…」命中偏好规则，应抽取并落库 1 条事实");
            }
        }

        @Test
        @DisplayName("提交 null 任务不抛异常")
        void toleratesNullTask() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            try (MemoryWritebackQueue queue =
                         new MemoryWritebackQueue(fixture.manager(), fixture.recorder())) {

                assertEquals(0, queue.submit(null));
                assertEquals(0, queue.depth());
            }
        }

        @Test
        @DisplayName("关闭时把在途任务排空，不丢已受理的回写")
        void drainsPendingOnClose() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            MemoryWritebackQueue queue =
                    new MemoryWritebackQueue(fixture.manager(), fixture.recorder());
            for (int i = 0; i < 3; i++) {
                queue.submit(new MemoryWritebackQueue.Task("t1", "u1", TRACE, "s1",
                        turn("第 " + i + " 轮用户消息", "第 " + i + " 轮回复")));
            }

            queue.close();

            assertEquals(3, queue.consumedCount(), "close 应等待在途任务处理完再返回");
            assertEquals(0, queue.failedCount());
        }
    }

    @Nested
    @DisplayName("失败与背压")
    class FailureAndBackpressure {

        @Test
        @DisplayName("抽取抛异常时记 failed 审计且不向外抛（不影响已返回的回复）")
        void extractFailureIsRecordedNotThrown() {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            // 失败点放在存储层：持久化阶段抛异常，抽取与队列逻辑全部真实
            MemoryWritebackQueue queue = new MemoryWritebackQueue(
                    fixture.managerOn(new FailingPersistRepository()), fixture.recorder());

            queue.submit(new MemoryWritebackQueue.Task("t1", "u1", TRACE, "s1",
                    turn("以后都用 Excel 导出报表", "好的")));
            await("失败被计数", () -> queue.failedCount() == 1);
            await("失败审计落库", () -> !fixture.audit(TRACE).isEmpty());

            assertEquals(0, queue.consumedCount());
            assertEquals(0, fixture.sink().size());
            assertEquals(MemorySpanAttributes.STATUS_FAILED, fixture.audit(TRACE).get(0).status());
            queue.close();
        }

        @Test
        @DisplayName("队列积压到上限时拒绝新任务并留可归因的 dropped 审计（宁可漏记也不无界增长）")
        void dropsWhenQueueFullAndRecordsAudit() throws Exception {
            MemoryEngineFixture fixture = MemoryEngineFixture.fresh();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            BlockingRepository blocker = new BlockingRepository(entered, release);
            MemoryWritebackQueue queue =
                    new MemoryWritebackQueue(fixture.managerOn(blocker), fixture.recorder());

            // 先占住唯一的消费者线程，使后续任务只能堆在队列里。
            // 内容必须命中抽取规则（偏好）—— 抽不出候选就走不到落库，也就不会阻塞
            queue.submit(new MemoryWritebackQueue.Task("t1", "u1", TRACE, "s1",
                    turn("以后都用 Excel 导出报表", "好的")));
            assertTrue(entered.await(10, TimeUnit.SECONDS), "消费者未进入阻塞点");
            await("队列腾空", () -> queue.depth() == 0);

            int accepted = 0;
            for (int i = 0; i < MemoryWritebackQueue.MAX_PENDING; i++) {
                accepted += queue.submit(new MemoryWritebackQueue.Task(
                        "t1", "u1", TRACE, "s1", turn("u" + i, "r" + i))) > 0 ? 1 : 0;
            }
            assertEquals(MemoryWritebackQueue.MAX_PENDING, accepted, "上限内应全部受理");

            // 走真实的回写入口（而不是直接调 submit）：要验证的正是「被丢弃时不发 pending 骨架」
            MemoryAwarePromptBuilder builder = new MemoryAwarePromptBuilder(
                    fixture.managerOn(blocker), fixture.recorder(), queue,
                    fixture.repository(), true);
            builder.afterReply(MemoryScope.of("t1", "u1", "s1"), TRACE, "溢出的一轮", "回复");

            assertEquals(1, queue.droppedCount());
            List<MemorySpanRecord> rows = fixture.audit(TRACE);
            assertEquals(1, rows.size(), "丢弃必须按原 traceId 留痕，才答得出「谁的记忆被丢了」");
            assertEquals(MemorySpanAttributes.STATUS_DROPPED, rows.get(0).status());
            assertEquals(MemorySpanAttributes.PHASE_ASYNC, rows.get(0).phase());
            assertEquals("u1", rows.get(0).userId());
            assertFalse(rows.get(0).pending(),
                    "已被丢弃就不能再发 pending 骨架，否则留下永远等不到完成的假记录");

            release.countDown();
            queue.close();
        }
    }

    /** 持久化必失败的仓储桩（抽取照常，落库阶段炸）。 */
    private static final class FailingPersistRepository
            extends FakeOltpMemoryRepository {

        @Override
        public void saveAtomicMemory(L1AtomicMemory memory) {
            throw new IllegalStateException("落库故意失败");
        }

        @Override
        public void savePersona(L3Persona persona) {
            throw new IllegalStateException("落库故意失败");
        }
    }

    /** 处理必阻塞的仓储桩：让消费者卡在第一个任务上，从而制造可复现的队满场景。 */
    private static final class BlockingRepository
            extends FakeOltpMemoryRepository {

        private final CountDownLatch entered;
        private final CountDownLatch release;

        BlockingRepository(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public void saveAtomicMemory(L1AtomicMemory memory) {
            block();
        }

        @Override
        public void savePersona(L3Persona persona) {
            block();
        }

        private void block() {
            entered.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
