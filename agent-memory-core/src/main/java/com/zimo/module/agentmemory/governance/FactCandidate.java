package com.zimo.module.agentmemory.governance;

import java.util.List;

/**
 * 一条抽取出来的事实候选。
 *
 * <p>候选**还不是记忆**：它只是「这段对话里有一句值得长期保留的话」的判断结果，
 * 是否真的写入由上层（{@code MemoryManager}）决定 —— 中间隔了一层是为了让
 * 合并、白名单、敏感过滤等治理能力都有机会介入，而不是让抽取器直接落库。</p>
 *
 * @param content       事实内容（取自原句，不做改写 —— 改写会引入不可溯源的语义漂移）
 * @param category      建议类别：{@code persona} / {@code preference} / {@code history}
 * @param importance    重要性 0~1，由 {@link ImportanceScorer} 评估
 * @param sourceTraceId L0 溯源 ID，凭此可回到原始对话
 * @param sourceTs      原消息时间戳（毫秒）
 * @param pattern       命中的抽取模式名（稳定标识，可直接用于埋点聚合与效果复盘）
 */
public record FactCandidate(
        String content,
        String category,
        double importance,
        String sourceTraceId,
        long sourceTs,
        String pattern) {

    /**
     * 归一化：内容两侧空白去掉；类别缺失时回落到 {@code custom}。
     *
     * <p>不做更激进的清洗（去标点、统一大小写）—— 记忆内容要能原样回显给用户看，
     * 改得越少，溯源时越容易与原话对上。</p>
     */
    public FactCandidate {
        content = content == null ? "" : content.strip();
        category = category == null || category.isBlank() ? "custom" : category;
        pattern = pattern == null ? "" : pattern;
    }

    /**
     * 去重键：同一批抽取里内容相同即视为同一条事实。
     *
     * <p>按 {@code content + category + 溯源} 比较，不按整对象比较 —— 时间戳与模式名
     * 的差异属于「同一条事实的第二次观察」，不该被当成两条。</p>
     *
     * @return 去重键
     */
    public String dedupKey() {
        return category + "\u0000" + content;
    }

    /**
     * 是否与另一候选重复。
     *
     * @param other 另一候选
     * @return 去重键相同返回 {@code true}
     */
    public boolean duplicates(FactCandidate other) {
        return other != null && dedupKey().equals(other.dedupKey());
    }

    /**
     * 命中模式列表（当前仅一个模式，保留列表形态以便多模式命中的规则扩展）。
     *
     * @return 模式名列表
     */
    public List<String> patterns() {
        return pattern.isEmpty() ? List.of() : List.of(pattern);
    }
}
