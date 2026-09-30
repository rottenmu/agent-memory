package com.zimo.module.agentmemory.engine;

import com.zimo.module.agentmemory.governance.ExtractOutcome;
import com.zimo.module.agentmemory.governance.MemoryManager;
import com.zimo.module.agentmemory.model.L0RawLog;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 事实抽取异步回写队列（PRD §7.2、§9.2）。
 *
 * <p>所属模块：module-agent-memory 记忆感知引擎。<b>解决的时序问题</b>：抽取回写发生在回复
 * 返回之后，即链路<b>已经收尾</b>（{@code GenAiTraceObserver.onEnd} 已把容器
 * {@code remove} 并导出）。因此本队列的完成阶段<b>绝不</b>向 {@code TraceCollector} 追加
 * span，结果一律落 {@code memory_span} 审计表（原因详见 {@link MemorySpanRecorder}）。</p>
 *
 * <p><b>为什么带消息体而不是只带 traceId</b>：会话侧的 L0 记录用的是 {@code convo-} 前缀
 * 的自生成 traceId（{@code AiConversationMemory.recordTurnEvent}），并不是链路 traceId。
 * 若异步侧按链路 traceId 回查 L0，会一条都查不到 —— 表现为「回写静默空转」。
 * 因此任务自带本轮对话消息（内存构造，<b>不再重复落 L0</b>，避免 L0 被写两份、
 * {@code countL0Total} 虚高）。</p>
 *
 * <p><b>背压策略（PRD §9.2）</b>：队列上限 {@value #MAX_PENDING}，超限时<b>拒绝新任务</b>
 * 并记 WARN + 审计（{@code status=dropped}）。拒绝新任务而不是挤掉最老的：老任务已经等了
 * 很久，丢弃它等于白付等待成本；而「宁可漏记最近几轮，也不让队列无界增长」是可解释的取舍。
 * 丢弃必须留痕 —— 静默丢数据是本项目反复出现的问题类型。</p>
 *
 * <p><b>线程模型</b>：单消费者守护线程。单线程让处理顺序与入队顺序一致，便于排查；
 * 抽取是低延迟的规则档计算，单线程足以支撑（LLM 档默认关，见决策 D2）。</p>
 *
 * @author zimo
 * @since 2026-09-16
 */
public class MemoryWritebackQueue implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MemoryWritebackQueue.class);

    /** 队列积压上限；超过即拒绝新任务（PRD §9.2）。 */
    public static final int MAX_PENDING = 1000;

    /** 消费者轮询等待时长（毫秒）：既能及时退出，又不空转烧 CPU。 */
    private static final long POLL_MS = 200;

    /** 关闭时等待在途任务结束的上限（秒）。 */
    private static final long SHUTDOWN_WAIT_SECONDS = 5;

    /**
     * 一轮对话的回写任务。
     *
     * @param tenantId  租户 ID
     * @param userId    用户 ID
     * @param traceId   链路 ID（用于把异步结果与同步骨架审计对齐）
     * @param sessionId 会话 ID
     * @param messages  本轮对话消息（内存构造，不落 L0）
     */
    public record Task(String tenantId, String userId, String traceId, String sessionId,
                       List<L0RawLog> messages) {
    }

    private final MemoryManager memoryManager;
    private final MemorySpanRecorder recorder;
    private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>(MAX_PENDING);
    private final ExecutorService worker;
    private final AtomicInteger consumed = new AtomicInteger();
    private final AtomicInteger dropped = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private volatile boolean running = true;

    /**
     * @param memoryManager 记忆门面（抽取与落库的唯一入口，保证脱敏规则不被绕过）
     * @param recorder      审计记录器
     */
    public MemoryWritebackQueue(MemoryManager memoryManager, MemorySpanRecorder recorder) {
        this.memoryManager = memoryManager;
        this.recorder = recorder;
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-memory-writeback");
            // 守护线程：进程退出时不因它被卡住（抽取是尽力而为的旁路能力）
            thread.setDaemon(true);
            return thread;
        });
        this.worker.submit(this::consume);
    }

    /**
     * 提交一轮对话的回写任务。
     *
     * @return 提交后的队列深度；返回 {@code -1} 表示队满被丢弃
     */
    public int submit(Task task) {
        if (task == null) {
            return queue.size();
        }
        boolean accepted = queue.offer(task);
        if (!accepted) {
            dropped.incrementAndGet();
            log.warn("[memory-writeback] 队列积压已达上限 {}，丢弃本次回写 traceId={}（已丢弃 {} 次）",
                    MAX_PENDING, task.traceId(), dropped.get());
            // 保留 traceId：丢弃必须可归因，否则查不出「谁的哪一轮记忆被丢了」
            recorder.recordDroppedWriteback(task.tenantId(), task.userId(), task.traceId(),
                    task.sessionId(), 0L);
            return -1;
        }
        return queue.size();
    }

    /** 当前积压条数（诊断用）。 */
    public int depth() {
        return queue.size();
    }

    /** 累计已处理条数。 */
    public int consumedCount() {
        return consumed.get();
    }

    /** 累计丢弃条数。 */
    public int droppedCount() {
        return dropped.get();
    }

    /** 累计失败条数。 */
    public int failedCount() {
        return failed.get();
    }

    /** 消费循环：单线程顺序处理，直到关闭且队列排空。 */
    private void consume() {
        while (running || !queue.isEmpty()) {
            Task task;
            try {
                task = queue.poll(POLL_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (task != null) {
                handle(task);
            }
        }
    }

    /** 处理单个任务：抽取 → 落库 → 写审计。异常必须留痕，不允许静默。 */
    private void handle(Task task) {
        long start = System.currentTimeMillis();
        try {
            ExtractOutcome outcome = memoryManager.extractFromMessages(
                    task.tenantId(), task.userId(), task.traceId(), task.sessionId(),
                    task.messages(), true);
            consumed.incrementAndGet();
            recorder.recordAsyncWriteback(task.tenantId(), task.userId(), task.traceId(),
                    task.sessionId(), outcome.persisted(), null,
                    System.currentTimeMillis() - start, MemorySpanAttributes.STATUS_OK);
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            log.warn("[memory-writeback] 异步回写失败 traceId={}（不影响已返回的回复）：{}",
                    task.traceId(), e.toString());
            recorder.recordAsyncWriteback(task.tenantId(), task.userId(), task.traceId(),
                    task.sessionId(), 0, null, System.currentTimeMillis() - start,
                    MemorySpanAttributes.STATUS_FAILED);
        }
    }

    /**
     * 关闭队列：停止接收新任务，等待在途任务结束。
     *
     * <p>等待有上限（{@value #SHUTDOWN_WAIT_SECONDS} 秒）：抽取属于旁路能力，
     * 关闭流程不能因为它未完成而长期阻塞。</p>
     */
    @Override
    public void close() {
        running = false;
        worker.shutdown();
        try {
            if (!worker.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                worker.shutdownNow();
                log.warn("[memory-writeback] 关闭超时，剩余 {} 条未处理", queue.size());
            }
        } catch (InterruptedException e) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
