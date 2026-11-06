package com.zimo.agent.memory.governance;

/**
 * 一次打分的分数分解。
 *
 * <p>三项分开返回而不是只给总分，是为了能回答「这条为什么排在前面」——
 * 排查「明明相关的记忆没被召回」时，必须能区分是 <b>没有匹配上</b>（{@code match} 低）、
 * <b>太旧了</b>（{@code decay} 低）还是 <b>被当成了不重要</b>（{@code importance} 低）。
 * 这些值同时是 {@code memory.score.*} 埋点属性的来源。</p>
 *
 * @param score      加权总分（0~1）
 * @param match      查询相关性分量（0~1），非语义相似度
 * @param decay      时间新鲜度分量（0~1）
 * @param importance 条目重要性分量（0~1）
 */
public record MemoryScore(double score, double match, double decay, double importance) {

    /** 仅给总分、三项置 0（用于不参与打分的场景，避免到处写 {@code new MemoryScore(x, 0, 0, 0)}）。 */
    public static MemoryScore ofTotal(double score) {
        return new MemoryScore(score, 0, 0, 0);
    }
}
