package com.zimo.agent.memory.governance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 记忆召回结果（S1 混合打分的对外契约）。
 *
 * <p>所属模块：module-agent-memory 记忆治理。用途：召回出参，直接对应
 * {@code POST /api/ai/memory/recall} 与 MCP {@code memory_recall} 的返回体。</p>
 *
 * <p><b>为什么同时返回「候选数」与「返回数」</b>：只给 {@code items} 无法区分
 * 「库里本来就没有相关记忆」和「有 200 条候选但都被 topK 挡住了」。这两种情况
 * 对运维的含义相反：前者是记忆没积累，后者是 topK 配小了。因此候选数、返回数、
 * 注入数三者必须分开暴露。</p>
 *
 * <p><b>为什么不过滤低于注入阈值的条目</b>：阈值（`agent-memory.recall.min-score`）
 * 的语义是「低于此分**不注入 Prompt**」，不是「不返回」。若在召回层就丢掉低分条目，
 * 调试时既看不到「到底打了多少分」，也无法验证「阈值是否只影响注入、不影响其他」。
 * 因此这里如实返回，由每条 {@link Item#meetsThreshold(double)} 与 {@link #injectedCount()}
 * 标出注入边界；真正执行过滤的是 M3 的注入层。</p>
 *
 * <p>本类不负责打分，也不负责排序 —— 打分与排序口径统一在 {@link MemoryScorer}，
 * 本类只承载结果，避免出现第二套口径。</p>
 *
 * @author zimo
 * @since 2026-09-15
 * @param algorithm      打分算法标识，恒为 {@link MemoryScorer#ALGORITHM_S1_HYBRID}；
 *                       保留该字段是为了将来上线 S2 后能做 A/B 归因（PRD §3.2 埋点要求）
 * @param query          本次召回所用查询文本；为空表示只按时间与重要性排序
 * @param topK           生效的返回条数上限（含调用方覆盖值）
 * @param minScore       生效的注入阈值（仅用于标记，不影响本结果的条数）
 * @param candidateLimit 候选池扫描上限
 * @param scanned        实际拉取的候选条数（类型过滤前），用于判断扫描是否截断
 * @param candidates     实际参与打分的条数（类型过滤后）
 * @param items          按分数降序返回的条目，长度不超过 {@code topK}
 */
public record RecallResult(
        String algorithm,
        String query,
        int topK,
        double minScore,
        int candidateLimit,
        int scanned,
        int candidates,
        List<Item> items) {

    /** 归一化：条目列表不可变，{@code null} 视为空列表。 */
    public RecallResult {
        algorithm = algorithm == null || algorithm.isBlank()
                ? MemoryScorer.ALGORITHM_S1_HYBRID : algorithm;
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * 候选池是否被扫描上限截断。
     *
     * <p><b>语义边界</b>：因 {@code topK} 未入选**不算**截断 —— 那是 top-K 的定义；
     * 只有候选池本身被 {@code candidateLimit} 截断才算，因为这种情况下可能存在
     * 「分数更高但根本没进候选池」的记忆被静默漏掉，属于 PRD 风险 R7 同类问题。</p>
     *
     * <p>判据用 {@link #scanned} 而不是 {@link #candidates}：类型过滤发生在候选拉取之后，
     * 若用过滤后的条数判断，会出现「拉满 500 条、过滤后只剩 3 条」被误判为未截断。</p>
     *
     * <p>{@code @JsonProperty} 必需：Jackson 序列化 record 时<b>只认 record 组件</b>，
     * 派生访问器默认不进 JSON。少了这个注解，本字段在 HTTP 层静默消失 ——
     * 直接调方法的单测仍会全绿，前端却永远看不到截断信号。</p>
     *
     * @return 拉取的候选条数达到扫描上限时返回 {@code true}
     */
    @JsonProperty("truncated")
    public boolean truncated() {
        return scanned >= candidateLimit;
    }

    /**
     * 达到注入阈值的条数（M3 注入 Prompt 时真正会带上的条数）。
     *
     * @return 分数不低于 {@link #minScore} 的条目数
     */
    @JsonProperty("injectedCount")
    public int injectedCount() {
        int count = 0;
        for (Item item : items) {
            if (item.meetsThreshold(minScore)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 因 topK 限制而未入选的候选条数。
     *
     * @return 候选数与返回条数之差（不小于 0）
     */
    @JsonProperty("rankedOutCount")
    public int rankedOutCount() {
        return Math.max(0, candidates - items.size());
    }

    /**
     * 召回命中的单条记忆。
     *
     * @param memoryId     记忆主键（L1 的 id 或 L3 的 id，删除时两表都需尝试）
     * @param layer        所属层：{@code l1} 原子记忆 / {@code l3} 画像
     * @param category     记忆类别（L1 为 {@code memory_type}，L3 为 {@code persona_type}）
     * @param content      记忆内容（已脱敏形态）
     * @param ts           条目自身时间戳（毫秒），**不是查询时间**（缺陷 D11）
     * @param score        综合分（0~1）
     * @param match        内容匹配度（0~1），字符级 bigram 混合，非语义相似度
     * @param decay        时间衰减因子（0~1）
     * @param importance   条目重要性（0~1）
     * @param accessCount  历史召回次数
     * @param lastAccessAt 最近召回时间（毫秒）；从未召回时为 {@code null}
     */
    public record Item(
            String memoryId,
            String layer,
            String category,
            String content,
            long ts,
            double score,
            double match,
            double decay,
            double importance,
            int accessCount,
            Long lastAccessAt) {

        /**
         * 是否达到注入阈值（决定这条记忆会不会被写进 Prompt）。
         *
         * <p><b>判据是「综合分达标」且「确有内容相关性」，两者缺一不可。</b></p>
         *
         * <h2>为什么必须同时要求相关性（真机验证发现的缺陷）</h2>
         * <p>综合分是三个分量的加权和：{@code score = w_match·match + w_decay·decay +
         * w_importance·importance}，默认权重 {@code 0.5 / 0.3 / 0.2}。若判据只看 {@code score}，
         * 则一条<b>内容毫不相关</b>（{@code match = 0}）但够新（{@code decay ≈ 1}）的记忆，
         * 得分是 {@code 0.3 + 0.2·importance}，最大可达 <b>0.5</b> —— 高于默认阈值 0.25。
         * 于是「阈值」对这类条目完全不起作用，<b>每轮对话都会把它们注入 Prompt</b>，
         * 而这正是 PRD 风险 R3（过期/无关记忆被当作事实）要防的事。</p>
         *
         * <p>真机复现：写 2 条「报表导出用 Excel」，再用一句完全不相关的话发起对话，
         * 召回仍返回 2 条、{@code score.top = 0.4}（{@code match = 0}、
         * {@code decay = 1.0}、{@code importance = 0.5}），按旧判据会被注入。</p>
         *
         * <p>{@code match > 0} 的语义是「至少有一个内容单元真的命中」——比调高阈值更准确：
         * 调高阈值（例如 0.5）只能把噪声挡在门外，无法区分「相关但旧」与「无关但新」。</p>
         *
         * @param minScore 注入阈值
         * @return 综合分不低于阈值<b>且</b>内容匹配度大于 0 时返回 {@code true}
         */
        public boolean meetsThreshold(double minScore) {
            return match > 0 && score >= minScore;
        }
    }
}
