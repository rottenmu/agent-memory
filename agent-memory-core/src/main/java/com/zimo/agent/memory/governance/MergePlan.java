package com.zimo.agent.memory.governance;

import java.util.List;

/**
 * 一次记忆合并的计划：把哪些条目并成一条、保留谁、保留者变成什么样。
 *
 * <p><b>为什么先出计划再执行</b>：合并是**不可逆**的（被合并的条目会被删除），而判据是
 * 文本近似度 —— 规则调松一点就可能把两条不同的事实并成一条。把「决策」与「落库」分成两步，
 * 才能让 {@code dryRun} 真正有意义：先看清单，再决定要不要动手。</p>
 *
 * @param groups 合并分组（仅包含需要动作的组：组内条目数 &gt; 1）
 */
public record MergePlan(List<Group> groups) {

    /** 空计划。 */
    public static final MergePlan EMPTY = new MergePlan(List.of());

    /** 归一化：分组列表不可变。 */
    public MergePlan {
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    /**
     * 一组将被归并的近似条目。
     *
     * @param keeperId            保留者的记忆 id（内容不动，只更新治理字段）
     * @param keeperContent       保留者内容（供 dryRun 输出给人看）
     * @param mergedIds           将被删除的 id 列表
     * @param mergedContents      被删除条目的内容（供 dryRun 审查「是否真的近似」）
     * @param resultingImportance 保留者合并后的重要性（取组内最大值）
     * @param resultingAccessCount 保留者合并后的访问计数（组内累加）
     */
    public record Group(
            String keeperId,
            String keeperContent,
            List<String> mergedIds,
            List<String> mergedContents,
            double resultingImportance,
            int resultingAccessCount) {

        /** 归一化：id 与内容列表不可变。 */
        public Group {
            mergedIds = mergedIds == null ? List.of() : List.copyOf(mergedIds);
            mergedContents = mergedContents == null ? List.of() : List.copyOf(mergedContents);
        }

        /** 本组减少的条目数。 */
        public int removedCount() {
            return mergedIds.size();
        }
    }

    /** 将被删除的条目总数。 */
    public int removedCount() {
        return groups.stream().mapToInt(Group::removedCount).sum();
    }

    /** 涉及的条目总数（保留者 + 被合并者）。 */
    public int involvedCount() {
        return groups.stream().mapToInt(group -> group.removedCount() + 1).sum();
    }

    /** 没有任何可合并的分组。 */
    public boolean isEmpty() {
        return groups.isEmpty();
    }
}
