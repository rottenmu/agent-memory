package com.zimo.module.agentmemory.autoconfig;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * module-agent-memory 配置属性。
 *
 * @param h2Url             H2 OLTP 数据库 JDBC URL（MVStore 文件模式）
 * @param olapArrowDataFile OLAP Arrow IPC 数据文件路径
 * @param syncCron          ETL 定时同步 cron 表达式
 * @param whitelistCategories 记忆类别白名单（逗号分隔）；为空表示放行全部
 * @param sensitiveFiltering 记忆敏感内容过滤开关
 * @param oltpEngine        OLTP 存储引擎（存储后端 SPI 路由）：h2（默认）/ mysql / rocksdb 等
 * @param olapEngine        OLAP 分析引擎（存储后端 SPI 路由）：arrow（默认）/ duckdb 等
 * @param fileBaseDir       文件兼容模式记忆根目录（{baseDir}/{agentId}/MEMORY.md）
 * @param mcpToken          记忆 MCP 端点（/api/agent-memory/mcp）访问令牌；为空时退化为
 *                          「仅回环来源可访问」。建议经环境变量 {@code AGENT_MEMORY_TOKEN} 注入
 * @param recall            S1 召回打分参数（见 {@link Recall}）
 * @param extract           事实抽取参数（见 {@link Extract}）
 * @param engine            记忆感知引擎开关（见 {@link Engine}）
 */
@ConfigurationProperties(prefix = "agent-memory")
public record AgentMemoryProperties(
        String h2Url,
        String olapArrowDataFile,
        String syncCron,
        String whitelistCategories,
        boolean sensitiveFiltering,
        String oltpEngine,
        String olapEngine,
        String fileBaseDir,
        String mcpToken,
        Recall recall,
        Extract extract,
        Engine engine) {

    /** 默认 H2 文件库（MVStore，数据落盘 data/agent-memory）。 */
    public static final String DEFAULT_H2_URL = "jdbc:h2:file:./data/agent-memory;MODE=LEGACY";
    /** 默认 OLAP Arrow IPC 文件。 */
    public static final String DEFAULT_OLAP_FILE = "./data/olap/l0_log.arrow";
    /** 默认同步间隔：每分钟。 */
    public static final String DEFAULT_SYNC_CRON = "0 */1 * * * ?";
    /** OLTP 默认引擎（内置 H2 实现，engine 名与 {@code H2OltpStorageProvider} 对应）。 */
    public static final String DEFAULT_OLTP_ENGINE = "h2";
    /** OLAP 默认引擎（内置 Arrow 实现，engine 名与 {@code ArrowOlapStorageProvider} 对应）。 */
    public static final String DEFAULT_OLAP_ENGINE = "arrow";

    public AgentMemoryProperties {
        if (h2Url == null || h2Url.isBlank()) {
            h2Url = DEFAULT_H2_URL;
        }
        if (olapArrowDataFile == null || olapArrowDataFile.isBlank()) {
            olapArrowDataFile = DEFAULT_OLAP_FILE;
        }
        if (syncCron == null || syncCron.isBlank()) {
            syncCron = DEFAULT_SYNC_CRON;
        }
        if (oltpEngine == null || oltpEngine.isBlank()) {
            oltpEngine = DEFAULT_OLTP_ENGINE;
        }
        if (olapEngine == null || olapEngine.isBlank()) {
            olapEngine = DEFAULT_OLAP_ENGINE;
        }
        if (fileBaseDir == null || fileBaseDir.isBlank()) {
            fileBaseDir = "./data/agent-memory-files";
        }
        if (recall == null) {
            recall = new Recall(null, null, null, null);
        }
        if (extract == null) {
            extract = new Extract(null);
        }
        if (engine == null) {
            engine = new Engine(null);
        }
    }

    /**
     * 记忆感知引擎开关（PRD §3.3 / §7.2）。
     *
     * <p><b>默认开</b>：这是本模块从「被动记忆库」变成「主动记忆」的那一步，默认关掉等于
     * 该能力不存在；而它的失败路径全部被设计成不反噬对话（召回失败、留痕失败、队列满都只 WARN），
     * 所以「默认开」的风险可控。</p>
     *
     * <p>关闭后：不预召回、不注入、不写 L0 留痕、不入回写队列 —— 即完全退回 M2 的被动形态，
     * 便于线上出问题时一键止血。</p>
     *
     * @param enabled 引擎总开关，默认 {@code true}
     */
    public record Engine(Boolean enabled) {

        /** 缺省开关：开。 */
        public static final boolean DEFAULT_ENABLED = true;

        /** 归一化后的开关值（{@code null} 视为默认开）。 */
        public boolean enabledOrDefault() {
            return enabled == null ? DEFAULT_ENABLED : enabled;
        }
    }

    /**
     * S1 召回打分参数（实现规格见 {@code docs/plans/2026-09-15-agent-memory-module-prd.md} §3.2）。
     *
     * <p><b>权重和在构造期校验</b>：不等于 1 直接抛 {@link IllegalStateException}，让配置错误在
     * <b>启动期</b>暴露，而不是等服务起来之后才发现分数恒为 0（与敏感掩码组引用校验同一原则）。</p>
     *
     * @param weights      三个权重，顺序为 {@code [match, decay, importance]}，默认 0.5 / 0.3 / 0.2
     * @param halfLifeDays 时间衰减半衰期（天），默认 30
     * @param topK         召回返回条数上限，默认 8
     * @param minScore     注入阈值，低于此分不注入 Prompt，默认 0.25
     */
    public record Recall(List<Double> weights, Integer halfLifeDays, Integer topK, Double minScore) {

        /** 默认权重：match 0.5 / decay 0.3 / importance 0.2。 */
        public static final List<Double> DEFAULT_WEIGHTS = List.of(0.5, 0.3, 0.2);
        /** 默认半衰期（天）。 */
        public static final int DEFAULT_HALF_LIFE_DAYS = 30;
        /** 默认召回条数上限。 */
        public static final int DEFAULT_TOP_K = 8;
        /** 默认注入阈值。 */
        public static final double DEFAULT_MIN_SCORE = 0.25;
        /** 权重和比较容差（浮点累加误差）。 */
        private static final double SUM_TOLERANCE = 1e-6;

        public Recall {
            if (weights == null) {
                weights = DEFAULT_WEIGHTS;
            } else {
                // 必须先自己查 null 再 List.copyOf：copyOf 对含 null 的集合直接抛 NPE，
                // 那样 YAML 里写错一个空位（`weights: [0.5, , 0.2]`）就只剩一句 NullPointerException，
                // 丢掉了「哪个配置错了」这个最关键的信息。
                for (Double weight : weights) {
                    if (weight == null || weight < 0 || weight > 1) {
                        throw new IllegalStateException(
                                "agent-memory.recall.weights 的每个权重都必须落在 0~1 且不能为空，"
                                        + "当前为 " + weights);
                    }
                }
                weights = List.copyOf(weights);
            }
            if (halfLifeDays == null) {
                halfLifeDays = DEFAULT_HALF_LIFE_DAYS;
            }
            if (topK == null) {
                topK = DEFAULT_TOP_K;
            }
            if (minScore == null) {
                minScore = DEFAULT_MIN_SCORE;
            }
            validate(weights, halfLifeDays, topK, minScore);
        }

        /**
         * 校验全部参数，不合法直接失败。
         *
         * <p>启动期失败是**有意**的：参见 PRD §3.2「为什么权重和必须 = 1 且在启动期校验」——
         * 配置错必须在使用前暴露，不允许出现「服务起来了但分数恒为 0」。
         * 只有 {@code null}（未配置）走默认值；**显式配了非法值一律拒绝**，
         * 不做「悄悄换成默认值」这种静默兜底。</p>
         */
        private static void validate(
                List<Double> weights, int halfLifeDays, int topK, double minScore) {
            if (weights.size() != 3) {
                throw new IllegalStateException(
                        "agent-memory.recall.weights 必须恰好是 3 个数值（依次为 match / decay / importance），"
                                + "当前为 " + weights);
            }
            double sum = 0;
            for (Double weight : weights) {
                if (weight == null || weight < 0 || weight > 1) {
                    throw new IllegalStateException(
                            "agent-memory.recall.weights 的每个权重都必须落在 0~1，当前为 " + weights);
                }
                sum += weight;
            }
            if (Math.abs(sum - 1.0) > SUM_TOLERANCE) {
                throw new IllegalStateException(
                        "agent-memory.recall.weights 的权重之和必须等于 1（match / decay / importance），"
                                + "当前为 " + weights + "，之和 = " + sum
                                + "。请修正配置后重启；服务不会带着无意义的打分继续运行。");
            }
            if (halfLifeDays <= 0) {
                throw new IllegalStateException(
                        "agent-memory.recall.half-life-days 必须大于 0，当前为 " + halfLifeDays);
            }
            if (topK <= 0) {
                throw new IllegalStateException(
                        "agent-memory.recall.top-k 必须大于 0，当前为 " + topK);
            }
            if (minScore < 0 || minScore > 1) {
                throw new IllegalStateException(
                        "agent-memory.recall.min-score 必须落在 0~1，当前为 " + minScore);
            }
        }

        /** match 权重（查询相关性）。 */
        public double matchWeight() {
            return weights.get(0);
        }

        /** decay 权重（时间新鲜度）。 */
        public double decayWeight() {
            return weights.get(1);
        }

        /** importance 权重（条目重要性）。 */
        public double importanceWeight() {
            return weights.get(2);
        }
    }

    /**
     * 事实抽取参数。
     *
     * @param llmEnabled 是否启用 LLM 档抽取。**默认关**（决策 D2）：LLM 抽取决引入额外模型调用与延迟，
     *                   抽取在异步侧执行、失败可丢弃；一期只跑零成本的规则档，仅在压测 / 历史回填时显式打开。
     */
    public record Extract(Boolean llmEnabled) {

        public Extract {
            if (llmEnabled == null) {
                llmEnabled = Boolean.FALSE;
            }
        }
    }
}
