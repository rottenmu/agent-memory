package com.zimo.module.agentmemory.governance;

import com.zimo.module.agentmemory.model.UserMemoryRow;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 批量读取结果（按 id 集合查询用户长期记忆）。
 *
 * <p>所属模块：module-agent-memory 记忆治理。用途：批量读取出参，对应
 * {@code POST /api/ai/memory/batch/query}。</p>
 *
 * <p><b>为什么必须显式返回 {@code missing}</b>：只返回命中列表时，调用方无法区分
 * 「这批 id 一条都不存在」和「参数传错了」。而 {@code missing} 是删除前校验、导出前
 * 核对、回填后对账三类操作的关键输入。严格按请求顺序去重返回，便于调用方做差集比对。</p>
 *
 * <p><b>为什么按 id 查询要下推到 SQL</b>：若改为「分页扫全量再在内存里过滤 id」，
 * 一旦用户记忆超过扫描上限就会静默漏查（缺陷 D10 同类问题）。因此按 id 集合直接查库，
 * 不设隐式截断。</p>
 *
 * @author zimo
 * @since 2026-09-15
 * @param requested 请求的 id 数量（去重后）
 * @param found     命中的记忆行（L3 画像 ∪ L1 原子记忆）
 * @param missing   未命中的 id 列表；为空表示全部命中
 */
public record BatchQueryOutcome(int requested, List<UserMemoryRow> found, List<String> missing) {

    /** 归一化：两个列表均不可变，{@code null} 视为空列表。 */
    public BatchQueryOutcome {
        found = found == null ? List.of() : List.copyOf(found);
        missing = missing == null ? List.of() : List.copyOf(missing);
    }

    /**
     * 是否全部命中。
     *
     * <p>{@code @JsonProperty} 必需：Jackson 只序列化 record 组件，派生访问器默认不入 JSON。</p>
     *
     * @return 未命中列表为空时返回 {@code true}
     */
    @JsonProperty("allFound")
    public boolean allFound() {
        return missing.isEmpty();
    }

    /**
     * 命中率（未命中数 / 请求数）。
     *
     * <p>请求数为 0 时返回 0，避免出现 {@code NaN} 让前端图表整块空白。</p>
     *
     * @return 未命中占比（0~1）
     */
    @JsonProperty("missRatio")
    public double missRatio() {
        return requested == 0 ? 0.0 : (double) missing.size() / requested;
    }
}
