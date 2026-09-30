package com.zimo.module.agentmemory.engine;

/**
 * 记忆埋点属性名与取值常量（PRD §8.2）。
 *
 * <p>所属模块：module-agent-memory 记忆感知引擎。本类只放<b>字符串常量</b>，目的是让
 * 「属性名」与「取值」各只有一处定义 —— 属性名写错（如 {@code memory.recall.count}
 * 写成 {@code memory.recallCount}）不会编译报错、也不会运行时报错，只会让面板上的这一列
 * 永远是空，属于最难发现的静默失效。</p>
 *
 * <p><b>gen_ai.* 与 memory.* 的分工</b>：{@code gen_ai.*} 是 OTel 语义约定（复用
 * agent-trace 的既有常量），{@code memory.*} 是本模块扩展。前者表达「这是什么类型的步骤」，
 * 后者表达「这次记忆动作的业务量」。</p>
 */
public final class MemorySpanAttributes {

    private MemorySpanAttributes() {
    }

    /* ---------------- 属性名 ---------------- */

    /** 记忆动作：见下方 {@code OP_*} 取值。 */
    public static final String OPERATION = "memory.operation";
    /** 记忆所在层：l0 / l1 / l2 / l3 / working。 */
    public static final String LAYER = "memory.layer";
    /** 单条操作的记忆 ID。 */
    public static final String ID = "memory.id";
    /** 批量操作的记忆 ID（超 20 个截断）。 */
    public static final String IDS = "memory.ids";
    /** 记忆类型：episodic / semantic / procedural。 */
    public static final String TYPE = "memory.type";
    /** 租户标识（隔离审计用；隔离维度必须与业务查询口径一致）。 */
    public static final String TENANT_ID = "memory.tenant.id";
    /** 重要性（0~1）。 */
    public static final String IMPORTANCE = "memory.importance";
    /** 召回返回条数。 */
    public static final String RECALL_COUNT = "memory.recall.count";
    /** 召回截断前的候选条数。 */
    public static final String RECALL_CANDIDATES = "memory.recall.candidates";
    /** 召回最高分。 */
    public static final String RECALL_SCORE_TOP = "memory.recall.score.top";
    /** 召回平均分。 */
    public static final String RECALL_SCORE_AVG = "memory.recall.score.avg";
    /** 召回是否命中（{@code count > 0}）。 */
    public static final String RECALL_HIT = "memory.recall.hit";
    /** 召回是否被上限截断（缺陷 D10 的可见化）。 */
    public static final String RECALL_TRUNCATED = "memory.recall.truncated";
    /** 召回策略：prefetch / tool。 */
    public static final String RECALL_STRATEGY = "memory.recall.strategy";
    /** 召回算法：本期恒为 {@code s1_hybrid}；S2 上线后为 {@code s2_vector}，用于 A/B 对比。 */
    public static final String RECALL_ALGORITHM = "memory.recall.algorithm";
    /** 命中条目的最大累计访问数。 */
    public static final String ACCESS_COUNT = "memory.access.count";
    /** 抽取的新事实数。 */
    public static final String EXTRACT_NEW = "memory.extract.new";
    /** 抽取因重复被丢弃的候选数。 */
    public static final String EXTRACT_DEDUP = "memory.extract.dedup";
    /** 淘汰条数。 */
    public static final String EVICT_COUNT = "memory.evict.count";
    /** 衰减系数。 */
    public static final String DECAY_FACTOR = "memory.decay.factor";
    /** 是否触发脱敏（沿用既有 {@code sensitiveMasked} 语义）。 */
    public static final String SENSITIVE_MASKED = "memory.sensitive.masked";
    /** 是否异步阶段产生。 */
    public static final String ASYNC = "memory.async";
    /** 迟到 span 标记（链路已收尾后才产生的 span，异常态显式暴露）。 */
    public static final String ORPHAN = "memory.orphan";
    /** 注入到 Prompt 的记忆条数（本模块扩展：用于验证「注入了几条」而非「召回了几条」）。 */
    public static final String INJECTED_COUNT = "memory.injected.count";

    /* ---------------- 操作取值 ---------------- */

    /** 召回。 */
    public static final String OP_RECALL = "recall";
    /** 写入。 */
    public static final String OP_WRITE = "write";
    /** 更新。 */
    public static final String OP_UPDATE = "update";
    /** 删除。 */
    public static final String OP_DELETE = "delete";
    /** 抽取。 */
    public static final String OP_EXTRACT = "extract";
    /** 合并。 */
    public static final String OP_MERGE = "merge";
    /** 淘汰。 */
    public static final String OP_EVICT = "evict";

    /* ---------------- 其它取值 ---------------- */

    /** 预召回（推理前主动注入）。 */
    public static final String STRATEGY_PREFETCH = "prefetch";
    /**
     * 计划模式预召回（M4-2a）：在预召回基础上额外拉取程序性记忆。
     *
     * <p>与 {@link #STRATEGY_PREFETCH} 分开记，是为了让标准 11 的判据能区分
     * 「计划模式特有的那次召回」与「普通预召回」。若两者共用一个取值，
     * 「计划模式会召回程序性记忆」这个结论就无法与「任何模式都会召回」区分开。</p>
     */
    public static final String STRATEGY_PREFETCH_PLAN = "prefetch_plan";
    /** 轮内按需检索（模型自主调用记忆工具）。 */
    public static final String STRATEGY_TOOL = "tool";
    /** 审计阶段：同步（链路内，trace 未收尾）。 */
    public static final String PHASE_SYNC = "sync";
    /** 审计阶段：异步（链路已收尾，只写审计表）。 */
    public static final String PHASE_ASYNC = "async";
    /** 状态：等待异步完成。 */
    public static final String STATUS_PENDING = "pending";
    /** 状态：成功。 */
    public static final String STATUS_OK = "ok";
    /** 状态：失败。 */
    public static final String STATUS_FAILED = "failed";
    /** 状态：被拒绝（白名单 / 敏感 / 越权）。 */
    public static final String STATUS_DENIED = "denied";
    /** 状态：因队列积压被丢弃。 */
    public static final String STATUS_DROPPED = "dropped";
    /** 层：L0 原始对话。 */
    public static final String LAYER_L0 = "l0";
    /** 层：L1 原子事实（抽取落库的默认层）。 */
    public static final String LAYER_L1 = "l1";
    /** 层：L2 情景聚合。 */
    public static final String LAYER_L2 = "l2";
    /** 层：L3 画像。 */
    public static final String LAYER_L3 = "l3";
}
