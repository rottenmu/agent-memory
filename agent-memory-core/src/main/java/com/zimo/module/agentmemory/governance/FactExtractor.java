package com.zimo.module.agentmemory.governance;

import com.zimo.module.agentmemory.model.L0RawLog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 事实抽取器（规则档）：从 L0 对话轮次里识别值得长期保留的事实候选。
 *
 * <p>实现规格见 {@code docs/plans/2026-09-15-agent-memory-module-prd.md} §3.1「抽取双档」。
 * 规则档默认开启、零成本、可离线回归；LLM 档（决策 D2）默认关闭，配置项
 * {@code agent-memory.extract.llm-enabled} 为 {@code true} 时才尝试启用。</p>
 *
 * <p><b>只从用户消息抽取</b>（{@code role=user}）。这不是保守，而是必须：抽取结果会落库、
 * 并在后续对话里被召回注入 Prompt。若把模型自己的输出当成用户事实记下来，就会形成
 * 「模型说了 → 记成事实 → 召回给模型 → 模型据此再确认」的自我强化回路，
 * 错误一旦进入就没有纠错入口。<b>「任务结论」同样只认用户侧的表达</b>
 * （用户说「那就定方案 B」），assistant 的复述与推理一律不采信。</p>
 *
 * <p><b>切分粒度是「小句」而非「消息」</b>：一条消息常同时包含身份与偏好
 * （「我叫李雷，以后都用 Excel」），若按整条消息匹配，只能产出一条候选且内容冗长。
 * 因此按句末标点**与逗号**切分，每段独立判定 —— 代价是含逗号的单句事实会被拆开，
 * 但拆开后每半仍可独立阅读，且能显著提高召回。</p>
 *
 * <p><b>纯函数</b>：不持可变状态、不做 IO、不读时钟，同样输入必得同样输出。</p>
 */
public final class FactExtractor {

    /** 抽取模式名：身份信息。 */
    public static final String PATTERN_IDENTITY = "identity_statement";
    /** 抽取模式名：偏好与长期指令。 */
    public static final String PATTERN_PREFERENCE = "preference_directive";
    /** 抽取模式名：任务结论。 */
    public static final String PATTERN_CONCLUSION = "task_conclusion";
    /** 抽取模式名：显式要求记住。 */
    public static final String PATTERN_REMEMBER = "explicit_remember";

    /** 抽取模式产出的类别（与 {@code AiMemoryService} 的类别语义一致）。 */
    public static final String CATEGORY_PERSONA = "persona";
    /** 偏好类别。 */
    public static final String CATEGORY_PREFERENCE = "preference";
    /** 历史/结论类别。 */
    public static final String CATEGORY_HISTORY = "history";
    /** 显式要求记住但类别不明的兜底类别。 */
    public static final String CATEGORY_CUSTOM = "custom";
    /**
     * 程序性记忆类别：可复用的「怎么做」经验（M4 新增）。
     *
     * <p><b>为什么复用 {@code category} 而不新建 {@code memory_type} 列</b>：PRD §五 的
     * {@code memory_type}（episodic/semantic/procedural）与本模块实际使用的 {@code category}
     * 是<b>同一件事的两种命名</b>（都是「这条记忆是什么」）。并存会产生两套可能不一致的真相，
     * 且加列要动 H2 表结构 + 写入 / 查询全路径。标准 11 只要求
     * {@code memory.type=procedural} <b>出现在召回 span 上</b>，从 {@code category} 派生即可满足。
     * 这是一处对 PRD 的显式偏离，已登记在 M4 方案与 PRD §十四。</p>
     *
     * <p><b>注意：本类别不由对话抽取自动产出</b>。程序性记忆来自计划执行完成后的经验回写
     * （M4-2b）与人工写入，语义是「这类任务通常怎么做」，而非「用户说了什么」。
     * 因此 {@link #RULES} 里<b>故意不登记</b> procedural 规则 —— 抽取器面对的是用户的原话，
     * 把「以后都用 Excel」这类句子同时判成 preference 与 procedural 会造出重复记忆。</p>
     */
    public static final String CATEGORY_PROCEDURAL = "procedural";

    /** 参与抽取的角色：仅用户消息。 */
    private static final Set<String> EXTRACTABLE_ROLES = Set.of("user");

    /**
     * 小句最小长度（去空白后）：太短的小句无法独立表达一条事实。
     *
     * <p>阈值取 4 与 {@link ImportanceScorer} 的「内容过短」口径保持一致 —— 两处若各定一套，
     * 会出现「抽取器认为够长、打分器认为过短」的矛盾，分值便无法解释。
     * 注意「我叫李雷」正好 4 字且是合法事实，阈值不能再高。</p>
     */
    private static final int MIN_CLAUSE_LENGTH = 4;

    /** 单条消息最多产出的候选数：防止一段长文把记忆库刷满。 */
    private static final int MAX_CANDIDATES_PER_MESSAGE = 5;

    /** 小句切分符：句末标点 + 换行 + 逗号。 */
    private static final Pattern CLAUSE_SPLITTER = Pattern.compile("[。！？!?；;\\n\\r]+|[，,]");

    /**
     * 抽取模式表。**顺序即优先级**：同一小句被多个模式命中时，取最先命中者 ——
     * 身份比偏好更具体（「我是数据平台的李雷，喜欢用 Excel」应记成画像而非偏好）。
     */
    private static final List<Rule> RULES = List.of(
            new Rule(PATTERN_IDENTITY, CATEGORY_PERSONA, Pattern.compile(
                    "(?i)(我的名字|我叫|我是(?!说|想|问|指|来|不)|我的职位|我的岗位|我的部门"
                            + "|我的团队|我负责|我的邮箱|我的工号|my name is)")),
            new Rule(PATTERN_PREFERENCE, CATEGORY_PREFERENCE, Pattern.compile(
                    "(?i)(以后都用|以后都|以后默认|从现在起|下次都用|每次都|默认用|一律"
                            + "|我喜欢|我偏好|我倾向|我更想|不要用|别用|优先(?:用|选|考)"
                            + "|请(?:用|使用|按)|prefer|always use|don't use)")),
            new Rule(PATTERN_CONCLUSION, CATEGORY_HISTORY, Pattern.compile(
                    "(?i)(结论是|结论：|最终方案|最终确定|最终采用|决定采用|确定用|已确认|已定下来"
                            + "|交付日期|验收标准是)")),
            new Rule(PATTERN_REMEMBER, CATEGORY_CUSTOM, Pattern.compile(
                    "(?i)(请记住|帮我记住|要记住|记住我|牢记|remember that)")));

    /** 抽取模式定义。 */
    private record Rule(String name, String category, Pattern pattern) {
    }

    /**
     * 一次抽取的结果。
     *
     * @param candidates   事实候选（已按批次内去重）
     * @param scanned      扫描的消息条数
     * @param deduped      因内容重复被丢弃的候选数
     * @param rejected     通过切分但过短而被丢弃的小句数
     * @param llmEnabled   配置是否要求启用 LLM 档
     * @param llmApplied   LLM 档**本次是否真的用上了**；恒为 {@code false}（见类注释）
     */
    public record Result(
            List<FactCandidate> candidates,
            int scanned,
            int deduped,
            int rejected,
            boolean llmEnabled,
            boolean llmApplied) {

        /** 归一化：候选列表不可变。 */
        public Result {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        /**
         * LLM 档是否处于「配置要求开启但实际未生效」的状态。
         *
         * <p>把这个状态显式暴露成接口字段，而不是只在日志里提一句：配置开了却没生效
         * 属于典型的静默失效，调用方必须能一眼看出来。</p>
         *
         * @return 需要开启但未生效时返回 {@code true}
         */
        public boolean llmConfiguredButInactive() {
            return llmEnabled && !llmApplied;
        }
    }

    private final ImportanceScorer importanceScorer;
    private final boolean llmEnabled;

    /**
     * @param importanceScorer 重要性评估器，用于给候选打分
     * @param llmEnabled       配置是否要求启用 LLM 档（当前实现恒不生效，见类注释）
     */
    public FactExtractor(ImportanceScorer importanceScorer, boolean llmEnabled) {
        this.importanceScorer = importanceScorer;
        this.llmEnabled = llmEnabled;
    }

    /**
     * 配置是否要求启用 LLM 档。
     *
     * <p>暴露该访问器是为了让装配层在启动日志里如实打印「LLM 抽取 开/关」——
     * 配置开关若不能在校验点被读到，运维只能靠翻 YAML 猜。</p>
     *
     * @return 配置要求开启时返回 {@code true}
     */
    public boolean llmEnabled() {
        return llmEnabled;
    }

    /**
     * 从一组 L0 日志中抽取事实候选。
     *
     * @param logs L0 原始日志（可为一个 trace 或一个会话的全部消息）
     * @return 抽取结果
     */
    public Result extract(List<L0RawLog> logs) {
        List<FactCandidate> candidates = new ArrayList<>();
        Map<String, FactCandidate> seen = new LinkedHashMap<>();
        int scanned = 0;
        int deduped = 0;
        int rejected = 0;
        for (L0RawLog log : logs == null ? List.<L0RawLog>of() : logs) {
            if (!isExtractable(log)) {
                continue;
            }
            scanned++;
            for (String clause : splitClauses(log.content())) {
                FactCandidate candidate = match(clause, log);
                if (candidate == null) {
                    continue;
                }
                if (seen.containsKey(candidate.dedupKey())) {
                    deduped++;
                    continue;
                }
                seen.put(candidate.dedupKey(), candidate);
                candidates.add(candidate);
            }
            if (candidates.size() > MAX_CANDIDATES_PER_MESSAGE) {
                // 单条消息产出过多说明规则过宽；宁可少抽，也不要让噪音淹没记忆库
                rejected += candidates.size() - MAX_CANDIDATES_PER_MESSAGE;
                candidates = new ArrayList<>(candidates.subList(0, MAX_CANDIDATES_PER_MESSAGE));
            }
        }
        return new Result(candidates, scanned, deduped, rejected, llmEnabled, false);
    }

    /**
     * 该日志是否参与抽取。
     *
     * @param log L0 日志
     * @return 角色为 {@code user} 且内容非空时返回 {@code true}
     */
    private static boolean isExtractable(L0RawLog log) {
        if (log == null || log.content() == null || log.content().isBlank()) {
            return false;
        }
        String role = log.role() == null ? "" : log.role().trim().toLowerCase(Locale.ROOT);
        return EXTRACTABLE_ROLES.contains(role);
    }

    /**
     * 切分小句并去掉空白项。
     *
     * @param content 消息原文
     * @return 小句列表
     */
    private static List<String> splitClauses(String content) {
        List<String> clauses = new ArrayList<>();
        for (String part : CLAUSE_SPLITTER.split(content)) {
            String clause = part.strip();
            if (!clause.isEmpty()) {
                clauses.add(clause);
            }
        }
        return clauses;
    }

    /**
     * 对一个小句判定抽取模式。
     *
     * @param clause 小句
     * @param log    来源日志
     * @return 命中的候选；无命中或过短时返回 {@code null}
     */
    private FactCandidate match(String clause, L0RawLog log) {
        if (clause.replaceAll("\\s+", "").length() < MIN_CLAUSE_LENGTH) {
            return null;
        }
        for (Rule rule : RULES) {
            if (!rule.pattern().matcher(clause).find()) {
                continue;
            }
            ImportanceScore assessed = importanceScorer == null
                    ? new ImportanceScore(ImportanceScorer.NEUTRAL, List.of())
                    : importanceScorer.score(clause, rule.category());
            return new FactCandidate(clause, rule.category(), assessed.value(),
                    log.traceId(), log.ts(), rule.name());
        }
        return null;
    }
}
