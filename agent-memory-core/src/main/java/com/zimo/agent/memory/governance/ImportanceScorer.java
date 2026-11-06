package com.zimo.agent.memory.governance;

import com.zimo.agent.memory.security.AiMemorySensitiveFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 重要性评估器（规则档）：把一条记忆映射到 0~1 的重要性分值。
 *
 * <p>实现规格见 {@code docs/plans/2026-09-15-agent-memory-module-prd.md} §3.1「重要性评估」。
 * 分值恒等于中性基线加上各类信号：</p>
 *
 * <pre>
 * value = clamp(0.5 + 类别调整 + Σ 内容信号调整)
 * </pre>
 *
 * <p><b>为什么用规则而不是模型</b>：重要性在写入路径上同步执行，若改为模型调用会把
 * 每一次记忆写入都变成一次带延迟与失败可能的外部依赖。规则档零成本、可离线回归，
 * 且「为什么打了这个分」天然可解释。LLM 档属于 §3.1 的抽取能力，由
 * {@code agent-memory.extract.llm-enabled} 控制且默认关闭（决策 D2）。</p>
 *
 * <p><b>两个区间必须区分</b>：</p>
 * <ul>
 *   <li>自动打分收敛到 {@code [0.05, 0.85]}；</li>
 *   <li>调用方**显式**指定的重要性允许到 {@code 1.0}。</li>
 * </ul>
 * <p>因为 PRD §7.3 规定「被显式标记为重要 → 免于遗忘淘汰」，而该标记的落库形态就是
 * {@code importance}。若显式值也被压到 0.85 以下，用户与智能体就失去了表达「这条绝不允许删」
 * 的手段。反过来，自动打分上限刻意低于 {@link #PROTECTED_THRESHOLD}，保证**保护状态只能来自
 * 显式声明**，不会被评分意外触发 —— 这条不变式在类加载时校验。</p>
 *
 * <p><b>纯函数</b>：不持可变状态、不做 IO、不读时钟，同样输入必得同样输出。</p>
 */
public final class ImportanceScorer {

    /** 中性基线：既无类别先验也无内容信号时的回落值（PRD 要求的「无信号回落 0.5」）。 */
    public static final double NEUTRAL = 0.5;
    /** 自动打分的下界：留一点余量，避免出现恒为 0 的「死值」。 */
    public static final double MIN_VALUE = 0.05;
    /** 自动打分的上界：必须严格小于 {@link #PROTECTED_THRESHOLD}。 */
    public static final double MAX_VALUE = 0.85;
    /** 免淘汰阈值：达到此值视为「被显式标记为重要」（PRD §7.3）。 */
    public static final double PROTECTED_THRESHOLD = 0.9;

    /** 信号名：类别先验。 */
    public static final String SIGNAL_CATEGORY_PRIOR = "category_prior";
    /** 信号名：显式长期指令。 */
    public static final String SIGNAL_EXPLICIT_LONG_TERM = "explicit_long_term";
    /** 信号名：身份陈述。 */
    public static final String SIGNAL_IDENTITY = "identity";
    /** 信号名：明确偏好指令。 */
    public static final String SIGNAL_PREFERENCE = "preference_directive";
    /** 信号名：疑问句（一次性问答，非长期事实）。 */
    public static final String SIGNAL_QUESTION = "question";
    /** 信号名：寒暄客套。 */
    public static final String SIGNAL_CHITCHAT = "chitchat";
    /** 信号名：命中敏感信息。 */
    public static final String SIGNAL_SENSITIVE = "sensitive";
    /** 信号名：内容过短。 */
    public static final String SIGNAL_TOO_SHORT = "too_short";

    /** 内容长度下限（去除空白后）：低于此长度几乎不可能是值得长期记住的事实。 */
    private static final int MIN_CONTENT_LENGTH = 4;

    /**
     * 类别先验：表达「这类记忆天然比偶发问答更值得记住」。
     *
     * <p>{@code history} 与 {@code custom} 不给调整量是有意的 —— 它们本身就是中性容器，
     * 是否重要完全取决于内容，交给内容信号判断。未知类别同样不加权，
     * 于是「未知类别 + 无内容信号」必然得到 {@link #NEUTRAL}。</p>
     */
    private static final Map<String, Double> CATEGORY_NUDGE = Map.ofEntries(
            Map.entry("persona", 0.15),
            Map.entry("preference", 0.12),
            Map.entry("global", 0.10),
            // 程序性记忆（M4）：可复用的「怎么做」经验，跨任务生效，
            // 天然比一次性任务结论更值得保留 —— 取 0.10，与 global 同级。
            Map.entry("procedural", 0.10),
            Map.entry("habit", 0.08),
            Map.entry("task", 0.05),
            Map.entry("fact", 0.02),
            Map.entry("session_var", -0.15));

    /** 内容信号规则。 */
    private record Rule(String name, double delta, Pattern pattern) {
    }

    /**
     * 内容信号表。
     *
     * <p>寒暄规则用**整串锚定**而非包含匹配：{@code 好的，以后都用 Excel} 这类「应答 + 指令」
     * 混合句式必须仍被识别为长期指令，若寒暄只做包含匹配，它会被白白扣掉 0.25 分。</p>
     */
    private static final List<Rule> CONTENT_RULES = List.of(
            new Rule(SIGNAL_EXPLICIT_LONG_TERM, 0.20, Pattern.compile(
                    "(?i)(以后都用|以后都|以后默认|从现在起|下次都用|每次都|记住|牢记|帮我记住"
                            + "|永远|始终|一律|默认用|remember|always|from now on)")),
            new Rule(SIGNAL_IDENTITY, 0.15, Pattern.compile(
                    "(?i)(我的名字|我叫|我是(?!说|想|问|指|来)|我的职位|我的岗位|我的部门"
                            + "|我负责|我的邮箱|my name is)")),
            new Rule(SIGNAL_PREFERENCE, 0.10, Pattern.compile(
                    "(?i)(我喜欢|我偏好|我倾向|我更想|不要用|别用|必须|优先(?:用|选|考)"
                            + "|请(?:用|使用|按)|prefer|don't use)")),
            new Rule(SIGNAL_QUESTION, -0.15, Pattern.compile("[?？]\\s*$")),
            new Rule(SIGNAL_CHITCHAT, -0.25, Pattern.compile(
                    "(?i)^\\s*(你好|您好|哈喽|嗨|在吗|谢谢|多谢|感谢|辛苦了|收到|好的|好滴|嗯"
                            + "|ok|okay|hi|hello|thanks|thank you|bye|再见)[\\s!！。~～,，]*$")));

    /**
     * 校验类级不变式：自动打分上界必须低于免淘汰阈值。
     *
     * <p>与 {@link AiMemorySensitiveFilter} 的掩码自检同理 —— 这类「上层语义依赖下层常量大小关系」
     * 的错误必须在启动时炸掉，而不是等到某天发现「自动打分的记忆莫名其妙免于淘汰」。</p>
     */
    static {
        if (MAX_VALUE >= PROTECTED_THRESHOLD) {
            throw new IllegalStateException(
                    "重要性自动打分上界（" + MAX_VALUE + "）必须严格小于免淘汰阈值（"
                            + PROTECTED_THRESHOLD + "），否则自动打分可能误触发淘汰保护");
        }
        if (MIN_VALUE < 0 || MIN_VALUE > NEUTRAL) {
            throw new IllegalStateException("重要性自动打分下界必须在 0~" + NEUTRAL + " 之间");
        }
    }

    private final AiMemorySensitiveFilter sensitiveFilter;

    /**
     * @param sensitiveFilter 敏感内容过滤器；复用写入路径同一实例，保证「脱敏」与「降权」口径一致
     */
    public ImportanceScorer(AiMemorySensitiveFilter sensitiveFilter) {
        this.sensitiveFilter = sensitiveFilter;
    }

    /* ---------------- 对外评估 ---------------- */

    /**
     * 评估一条记忆的重要性。
     *
     * @param content  记忆原文；应为**脱敏前**的原文，否则敏感降权信号无法命中
     * @param category 记忆类别（persona / preference / history / custom / global / session_var …）
     * @return 分值与其信号明细
     */
    public ImportanceScore score(String content, String category) {
        List<ImportanceScore.Signal> signals = new ArrayList<>();
        String normalizedCategory = category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
        Double nudge = CATEGORY_NUDGE.get(normalizedCategory);
        if (nudge != null && nudge != 0.0) {
            signals.add(new ImportanceScore.Signal(SIGNAL_CATEGORY_PRIOR, nudge, normalizedCategory));
        }
        collectContentSignals(content, signals);
        double total = NEUTRAL;
        for (ImportanceScore.Signal signal : signals) {
            total += signal.delta();
        }
        return new ImportanceScore(clampScored(total), signals);
    }

    /* ---------------- 区间与保护 ---------------- */

    /**
     * 自动打分的收敛区间：{@code [MIN_VALUE, MAX_VALUE]}。
     *
     * @param value 原始加权值；{@code NaN} 回落到中性基线
     * @return 收敛后的分值
     */
    public static double clampScored(double value) {
        if (Double.isNaN(value)) {
            return NEUTRAL;
        }
        return Math.max(MIN_VALUE, Math.min(MAX_VALUE, value));
    }

    /**
     * 显式重要性的收敛区间：{@code [0, 1]}。
     *
     * <p>刻意允许到 {@code 1.0} —— 这是「显式标记重要」的唯一表达途径（见类注释）。
     * {@code NaN} 回落到中性基线，而不是回落到 0：无法解析的入参不该被当成「最不重要」，
     * 否则一次格式错误就会让记忆进入最易被淘汰的区间。</p>
     *
     * @param value 调用方指定的重要性
     * @return 收敛后的分值
     */
    public static double clampExplicit(double value) {
        if (Double.isNaN(value)) {
            return NEUTRAL;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    /**
     * 是否受遗忘淘汰保护。
     *
     * @param importance 记忆的重要性
     * @return 达到 {@link #PROTECTED_THRESHOLD} 时返回 {@code true}
     */
    public static boolean protectedFromEviction(double importance) {
        return !Double.isNaN(importance) && importance >= PROTECTED_THRESHOLD;
    }

    /* ---------------- 内部工具 ---------------- */

    /**
     * 收集内容信号。
     *
     * @param content 记忆原文
     * @param signals 信号收集容器
     */
    private void collectContentSignals(String content, List<ImportanceScore.Signal> signals) {
        String text = content == null ? "" : content.toLowerCase(Locale.ROOT);
        for (Rule rule : CONTENT_RULES) {
            Matcher matcher = rule.pattern().matcher(text);
            if (matcher.find()) {
                signals.add(new ImportanceScore.Signal(rule.name(), rule.delta(), matcher.group()));
            }
        }
        if (sensitiveFilter != null && sensitiveFilter.containsSensitive(text)) {
            // 命中片段一律不回填：敏感值出现在接口响应或日志里，本身就是一次泄露
            signals.add(new ImportanceScore.Signal(SIGNAL_SENSITIVE, -0.20, ""));
        }
        if (text.replaceAll("\\s+", "").length() < MIN_CONTENT_LENGTH) {
            signals.add(new ImportanceScore.Signal(SIGNAL_TOO_SHORT, -0.10, ""));
        }
    }
}
