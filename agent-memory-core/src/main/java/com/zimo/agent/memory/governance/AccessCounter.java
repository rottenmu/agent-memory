package com.zimo.agent.memory.governance;

import com.zimo.agent.memory.storage.OltpMemoryRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 召回访问计数累加器：把「召回命中后 access_count + 1」从查询路径上摘下来。
 *
 * <p>所属模块：module-agent-memory 记忆治理。用途：进程内累加 + 定时批量落库，
 * 实现 PRD §3.2「计数更新不阻塞查询」。</p>
 *
 * <p><b>为什么不能同步写库</b>：召回的目标是 P95 ≤ 50ms（PRD §9.2）。若每次召回都对
 * 命中的每条记忆发起一次 {@code UPDATE}，一次召回就是 N 次写放大（N = topK），
 * 且这些写入迟早会在并发召回下互相锁等待 —— 用一条查询延迟去换一个只服务于
 * 衰减参考的计数字段，代价与收益完全不成比例。</p>
 *
 * <p><b>为什么累加的是「基准值 + 增量」而不是纯增量</b>：仓储的
 * {@link OltpMemoryRepository#updateAtomicGovernance} 接收的是绝对值而非增量
 * （刻意如此：绝对值写入天然幂等，重放不会把计数翻倍）。因此累加器在首次
 * {@link #touch} 时记录该条目的当前 {@code access_count} 作为基准，落库时写
 * 「基准 + 本次累计增量」。基准取自召回时读到的那份行数据，不额外查询。</p>
 *
 * <p><b>事务与一致性边界（明确接受的不精确）</b>：<br>
 * 1. 进程崩溃会丢失尚未 flush 的计数。PRD 明确接受这一点 —— 它只服务于衰减参考，不是账本。<br>
 * 2. 同一条目在两次 flush 之间被召回多次，计数会合并为一次写入，这正是期望行为。<br>
 * 3. 极端并发下（flush 读走快照后、写库前该条又被召回）可能少记增量，方向是「少记」
 * 而非「多记」，不会放大计数。<br>
 * 4. 仅累加 L1 原子记忆：仓储未提供 L3 画像的治理字段更新能力，L3 的访问计数本期不维护。</p>
 *
 * <p><b>内存上界</b>：待落库条目数受 {@link #MAX_PENDING} 约束，超出后拒绝新条目
 * （宁可少记也不让累加器无界增长 —— 长期不 flush 或海量不同 memoryId 都会撑爆堆）。</p>
 *
 * @author zimo
 * @since 2026-09-15
 */
public final class AccessCounter {

    /**
     * 待落库条目数上限。
     *
     * <p>取值理由：正常部署下 flush 周期 30 秒，单周期内被召回的不同 memoryId
     * 数量远小于此值。上限的作用是兜底「flush 未启动 / 定时器失效」这类异常，
     * 而不是削峰。</p>
     */
    public static final int MAX_PENDING = 20_000;

    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    /**
     * 记录一次召回命中。
     *
     * @param tenantId        租户标识（随条目记住：flush 的更新语句必须带租户条件，
     *                        否则同 id 跨租户会被一起改到）
     * @param memoryId        记忆主键；为空时忽略（保持方法对调用方宽容）
     * @param importance      条目当前重要性，落库时原样回写（本类不改重要性）
     * @param baseAccessCount 召回时读到的访问计数基准值
     * @param accessedAt      本次访问时间（毫秒）
     */
    public void touch(
            String tenantId, String memoryId, double importance, int baseAccessCount, long accessedAt) {
        if (memoryId == null || memoryId.isBlank() || tenantId == null || tenantId.isBlank()) {
            return;
        }
        Pending existed = pending.get(memoryId);
        if (existed != null) {
            existed.increase(accessedAt);
            return;
        }
        if (pending.size() >= MAX_PENDING) {
            // 触顶时直接放弃记录：计数不是账本，丢弃优于让累加器无界增长
            return;
        }
        Pending created = new Pending(tenantId, importance, baseAccessCount, accessedAt);
        Pending raced = pending.putIfAbsent(memoryId, created);
        if (raced != null) {
            // 并发下已被其他线程插入，本次并入既有条目，避免丢一次计数
            raced.increase(accessedAt);
        }
    }

    /** 当前待落库的条目数（供指标与测试观测）。 */
    public int pendingSize() {
        return pending.size();
    }

    /**
     * 取走当前待落库快照（取走后累加器即清空对应条目）。
     *
     * <p>用 {@code remove(key, value)} 做条件移除：只有当条目仍是快照里那一个时才摘除，
     * 保证「摘除瞬间被并发 touch 到的条目」会以新条目继续留在累加器里，而不是被清掉。</p>
     *
     * @return 条目 id → 待落库明细
     */
    public Map<String, Pending> drain() {
        Map<String, Pending> snapshot = new HashMap<>();
        for (Map.Entry<String, Pending> entry : pending.entrySet()) {
            if (pending.remove(entry.getKey(), entry.getValue())) {
                snapshot.put(entry.getKey(), entry.getValue());
            }
        }
        return snapshot;
    }

    /**
     * 把累加计数批量落库。
     *
     * <p>逐条更新而非拼一条大 SQL：{@code access_count} 是「基准 + 增量」的每行不同值，
     * 无法用单条语句表达；批量条数受 {@link #MAX_PENDING} 约束，不会无界膨胀。</p>
     *
     * <p>更新语句带各自条目的租户条件（见 {@link #touch} 的参数说明），因此本方法可以
     * 一次覆盖全部租户的待落库计数，不需要调用方按租户循环。</p>
     *
     * @param repository 仓储
     * @return 真实更新行数
     */
    public int flush(OltpMemoryRepository repository) {
        if (repository == null) {
            return 0;
        }
        int updated = 0;
        for (Map.Entry<String, Pending> entry : drain().entrySet()) {
            Pending item = entry.getValue();
            updated += repository.updateAtomicGovernance(item.tenantId(), entry.getKey(),
                    item.importance(), item.resultingAccessCount(), item.lastAccessedAt());
        }
        return updated;
    }

    /**
     * 直接在累加器上做累加的落库前置动作（测试与调试用）。
     *
     * <p>刻意只暴露只读遍历，不提供「修改待落库值」的入口：计数只应由真实召回产生，
     * 任何外部改写都会让指标失去意义。</p>
     *
     * @param consumer 逐条回调
     */
    public void forEachPending(Consumer<Pending> consumer) {
        if (consumer == null) {
            return;
        }
        pending.values().forEach(consumer);
    }

    /**
     * 单条目的待落库明细。
     *
     * <p>不实现为 record：它的字段是可变的累加状态（增量、最近访问时间会随每次召回变化），
     * 而 record 的语义是「值不可变」，用 record 会误导读者以为它是快照。</p>
     */
    public static final class Pending {

        private final String tenantId;
        private final double importance;
        private final int baseAccessCount;
        private final AtomicInteger delta = new AtomicInteger();
        private volatile long lastAccessedAt;

        Pending(String tenantId, double importance, int baseAccessCount, long accessedAt) {
            this.tenantId = tenantId;
            this.importance = importance;
            // 负数基准值按 0 处理：历史数据可能写入过异常值，不能让脏数据传播下去
            this.baseAccessCount = Math.max(0, baseAccessCount);
            this.lastAccessedAt = accessedAt;
            this.delta.set(1);
        }

        /** 租户标识，落库时作为更新条件。 */
        public String tenantId() {
            return tenantId;
        }

        /** 累加一次访问，并把最近访问时间推进到更晚的一次。 */
        void increase(long accessedAt) {
            delta.incrementAndGet();
            if (accessedAt > lastAccessedAt) {
                lastAccessedAt = accessedAt;
            }
        }

        /** 本次累计的增量（本周期内被召回次数）。 */
        public int delta() {
            return delta.get();
        }

        /** 落库应写入的绝对访问计数（基准 + 增量）。 */
        public int resultingAccessCount() {
            return baseAccessCount + delta.get();
        }

        /** 最近访问时间（毫秒）。 */
        public long lastAccessedAt() {
            return lastAccessedAt;
        }

        /** 条目重要性，落库时原样回写。 */
        public double importance() {
            return importance;
        }
    }
}
