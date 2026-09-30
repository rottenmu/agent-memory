package com.zimo.module.agentmemory.governance;

import com.zimo.module.agentmemory.model.L1AtomicMemory;
import com.zimo.module.agentmemory.storage.OltpMemoryRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆合并器：把同租户 + 同用户 + 同类别下的**近似条目**归并成一条。
 *
 * <p>实现规格见 {@code docs/plans/2026-09-15-agent-memory-module-prd.md} §3.1「记忆合并」。
 * 抽取器会反复观察到同一件事（用户三次提到「以后都用 Excel」），若每次都新写一条，
 * 记忆库会被同义重复撑满，召回时也会把同一个事实挤占多个 topK 名额。</p>
 *
 * <p><b>近似判定复用召回的打分器</b>（{@link MemoryScorer#match}），而不是另写一套
 * 字符串比较：这样「什么算像」在召回与合并两处只有一个定义，不会出现「召回想合并、
 * 合并想召回」的口径分裂。取双向 {@code max} —— 合并的典型场景正是
 * 「一条是另一条的前缀或扩展」（「以后都用 Excel」⊂「以后都用 Excel 导出报表」），
 * 单向覆盖率此时一边为 1、另一边偏低，取 {@code min} 会把该合并的漏掉。</p>
 *
 * <p><b>只比较同类别条目</b>：{@code persona}（我是谁）与 {@code preference}（我喜欢什么）
 * 即便文字高度重合也不该互并 —— 类别是召回时的过滤维度，合并会改变检索语义。</p>
 *
 * <p><b>计划与执行分离</b>：{@link #plan} 是纯函数（不 IO、不读时钟），
 * {@link #apply} 才落库；{@code dryRun} 只走前者。</p>
 */
public final class MemoryMerger {

    /**
     * 默认近似阈值。
     *
     * <p>取 0.8 而非 0.5：{@code match} 的 bigram 分量对中文短句很容易给出 0.4~0.6，
     * 阈值过低会把「我的部门是数据平台部」与「我的团队是数据平台部」这类**语义不同但用词相近**
     * 的事实合并，而合并不可逆。</p>
     */
    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.8;

    /** 单用户扫描上限：合并是离线治理动作，超过此规模应改用分批任务而非一次性拉全量。 */
    public static final int DEFAULT_SCAN_LIMIT = 2_000;

    /**
     * 保留者排序：重要性高者优先，其次访问多者、时间早者、id 小者。
     *
     * <p>「时间早者优先」是为了**不改写时间戳**：保留者若是组内最早的，合并后无需修改
     * {@code ts}，记忆的原始发生时间得以保持（改写 ts 会让溯源对不上原对话）。</p>
     */
    private static final Comparator<L1AtomicMemory> KEEPER_ORDER =
            Comparator.comparingDouble(L1AtomicMemory::importance).reversed()
                    .thenComparing(Comparator.comparingInt(L1AtomicMemory::accessCount).reversed())
                    .thenComparingLong(L1AtomicMemory::ts)
                    .thenComparing(L1AtomicMemory::id);

    private final MemoryScorer scorer;
    private final double similarityThreshold;

    /**
     * @param scorer              打分器，提供 {@code match} 相似度（不得为 {@code null}）
     * @param similarityThreshold 近似阈值，落在 0~1
     */
    public MemoryMerger(MemoryScorer scorer, double similarityThreshold) {
        if (scorer == null) {
            throw new IllegalArgumentException("MemoryMerger 需要 MemoryScorer 提供相似度判定");
        }
        if (similarityThreshold < 0 || similarityThreshold > 1) {
            throw new IllegalArgumentException(
                    "similarityThreshold 必须落在 0~1，当前为 " + similarityThreshold);
        }
        this.scorer = scorer;
        this.similarityThreshold = similarityThreshold;
    }

    /** 按默认阈值构造。 */
    public static MemoryMerger of(MemoryScorer scorer) {
        return new MemoryMerger(scorer, DEFAULT_SIMILARITY_THRESHOLD);
    }

    /* ---------------- 纯计算 ---------------- */

    /**
     * 产出合并计划（不改动任何数据）。
     *
     * @param items 待审查的条目（可跨类别，内部按类别分组后各自聚类）
     * @return 合并计划；无可合并项时返回 {@link MergePlan#EMPTY}
     */
    public MergePlan plan(List<L1AtomicMemory> items) {
        if (items == null || items.size() < 2) {
            return MergePlan.EMPTY;
        }
        Map<String, List<L1AtomicMemory>> byCategory = new LinkedHashMap<>();
        for (L1AtomicMemory item : items) {
            if (item == null || item.content() == null || item.content().isBlank()) {
                continue;
            }
            byCategory.computeIfAbsent(categoryOf(item), key -> new ArrayList<>()).add(item);
        }
        List<MergePlan.Group> groups = new ArrayList<>();
        for (List<L1AtomicMemory> sameCategory : byCategory.values()) {
            groups.addAll(cluster(sameCategory));
        }
        return groups.isEmpty() ? MergePlan.EMPTY : new MergePlan(groups);
    }

    /**
     * 在同类条目内贪心聚类。
     *
     * @param items 同类别条目
     * @return 需要动作的分组
     */
    private List<MergePlan.Group> cluster(List<L1AtomicMemory> items) {
        List<L1AtomicMemory> ordered = new ArrayList<>(items);
        ordered.sort(KEEPER_ORDER);
        boolean[] consumed = new boolean[ordered.size()];
        List<MergePlan.Group> groups = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            if (consumed[index]) {
                continue;
            }
            L1AtomicMemory keeper = ordered.get(index);
            List<L1AtomicMemory> merged = new ArrayList<>();
            for (int candidate = index + 1; candidate < ordered.size(); candidate++) {
                if (consumed[candidate]) {
                    continue;
                }
                if (similar(keeper.content(), ordered.get(candidate).content())) {
                    consumed[candidate] = true;
                    merged.add(ordered.get(candidate));
                }
            }
            if (merged.isEmpty()) {
                continue;
            }
            consumed[index] = true;
            groups.add(buildGroup(keeper, merged));
        }
        return groups;
    }

    /**
     * 两条内容是否近似（双向取大）。
     *
     * @param left  内容一
     * @param right 内容二
     * @return 双向 {@code match} 的较大者达到阈值时返回 {@code true}
     */
    public boolean similar(String left, String right) {
        double forward = scorer.match(left, right);
        if (forward >= similarityThreshold) {
            return true;
        }
        return scorer.match(right, left) >= similarityThreshold;
    }

    /**
     * 组装分组：重要性取组内最大，访问计数累加。
     *
     * <p>访问计数用 {@code long} 中间量后夹到 {@code int} 上界 —— 治理动作不该因为
     * 一个溢出的计数器把服务带崩。</p>
     */
    private static MergePlan.Group buildGroup(L1AtomicMemory keeper, List<L1AtomicMemory> merged) {
        double importance = keeper.importance();
        long accessCount = keeper.accessCount();
        List<String> mergedIds = new ArrayList<>();
        List<String> mergedContents = new ArrayList<>();
        for (L1AtomicMemory item : merged) {
            importance = Math.max(importance, item.importance());
            accessCount += Math.max(0, item.accessCount());
            mergedIds.add(item.id());
            mergedContents.add(item.content());
        }
        int clamped = (int) Math.min(accessCount, Integer.MAX_VALUE);
        return new MergePlan.Group(keeper.id(), keeper.content(), mergedIds, mergedContents,
                importance, clamped);
    }

    /* ---------------- 执行 ---------------- */

    /**
     * 一次合并的执行结果。
     *
     * @param plan         合并计划
     * @param scanned      扫描到的条目数
     * @param deletedCount 实际删除的条目数（{@code dryRun} 时为 0）
     * @param updatedCount 实际更新的保留者数（{@code dryRun} 时为 0）
     * @param dryRun       是否为预演
     */
    public record Outcome(
            MergePlan plan, int scanned, int deletedCount, int updatedCount, boolean dryRun) {
    }

    /**
     * 扫描并（可选）执行合并。
     *
     * @param repository 仓储
     * @param tenantId   租户标识
     * @param userId     用户标识
     * @param dryRun     为 {@code true} 时只产出计划，不落库
     * @return 执行结果
     */
    public Outcome apply(
            OltpMemoryRepository repository, String tenantId, String userId, boolean dryRun) {
        List<L1AtomicMemory> items =
                repository.recallAtomicByUser(tenantId, userId, DEFAULT_SCAN_LIMIT, 0);
        MergePlan plan = plan(items);
        if (dryRun || plan.isEmpty()) {
            return new Outcome(plan, items.size(), 0, 0, dryRun);
        }
        int deleted = 0;
        int updated = 0;
        for (MergePlan.Group group : plan.groups()) {
            updated += repository.updateAtomicGovernance(tenantId, group.keeperId(),
                    group.resultingImportance(), group.resultingAccessCount(), null);
            deleted += repository.deleteAtomicByIds(tenantId, group.mergedIds());
        }
        return new Outcome(plan, items.size(), deleted, updated, false);
    }

    /** 类别：{@code memoryType} 缺失时按 {@code custom} 处理，与写入路径同口径。 */
    private static String categoryOf(L1AtomicMemory memory) {
        String type = memory.memoryType();
        return type == null || type.isBlank() ? "custom" : type;
    }
}
