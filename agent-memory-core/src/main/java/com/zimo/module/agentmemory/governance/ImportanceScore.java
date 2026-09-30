package com.zimo.module.agentmemory.governance;

import java.util.List;

/**
 * 一次重要性评估的结果：分值 + 命中信号明细。
 *
 * <p>信号列表不是「附带说明」，而是分值的**唯一解释来源**：{@code value} 恒等于
 * {@link ImportanceScorer#NEUTRAL 中性基线} 加上所有 {@code delta} 之和（再经区间收敛）。
 * 这条不变式让「记忆为什么被淘汰」可复盘 —— 只看一个分值，无法区分是「内容本身没价值」
 * 还是「规则打错了分」，而后者才是真正需要修的 bug。</p>
 *
 * @param value   重要性 0~1
 * @param signals 命中的信号（按规则定义顺序排列；无命中时为空列表）
 */
public record ImportanceScore(double value, List<Signal> signals) {

    /**
     * 单条重要性信号。
     *
     * @param name    信号名（稳定标识，可直接用于埋点聚合）
     * @param delta   该信号对分值的调整量（可正可负）
     * @param matched 命中的原文片段；无具体片段的信号（内容过短、敏感命中）为空串
     */
    public record Signal(String name, double delta, String matched) {
    }

    /** 归一化：信号列表不可变，避免调用方修改已产出的评估结果。 */
    public ImportanceScore {
        signals = signals == null ? List.of() : List.copyOf(signals);
    }

    /**
     * 命中信号名列表，供接口返回与埋点使用。
     *
     * @return 信号名列表
     */
    public List<String> signalNames() {
        return signals.stream().map(Signal::name).toList();
    }

    /**
     * 是否命中了指定信号。
     *
     * @param name 信号名
     * @return 命中返回 {@code true}
     */
    public boolean has(String name) {
        return signals.stream().anyMatch(signal -> signal.name().equals(name));
    }
}
