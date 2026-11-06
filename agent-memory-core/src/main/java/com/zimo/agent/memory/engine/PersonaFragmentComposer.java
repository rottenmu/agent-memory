package com.zimo.agent.memory.engine;

import com.zimo.agent.memory.model.L0RawLog;
import com.zimo.agent.memory.model.L3Persona;
import com.zimo.agent.memory.storage.OltpMemoryRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * L3 用户画像片段组装器：把「跨会话稳定的用户画像」渲染成可注入系统提示词的一段文本。
 *
 * <p>所属模块：{@code agent-memory-core} 的 engine 层。与 L1 的
 * {@code MemoryAwarePromptBuilder#recallFragment} 并列 —— 两者共用同一注入点，
 * 但<b>生命周期完全不同</b>：L1 是每轮召回的变动内容，L3 是多轮不变的稳定内容。</p>
 *
 * <h2>为什么单独一个类，而不是塞进 PromptBuilder</h2>
 * <p>PromptBuilder 已经承担 L1 召回、阈值过滤、L0 留痕、异步回写四件事（306 行）。
 * L3 是<b>另一条数据来源</b>（按类别精确查画像表，不走打分召回），混进去会让"这段文本到底
 * 从哪来、为什么是这个顺序"变得不可读；也会把两类失败混进同一个 try/catch，
 * 表现为"画像查不到导致 L1 片段也不注入了"。</p>
 *
 * <h2>三条不变量</h2>
 * <ol>
 *   <li><b>去重</b>：同一段内容可能同时落在 persona 与 preference 两行（历史写入路径并存），
 *       重复渲染会让提示词里出现两条一模一样的"事实"，模型会误以为是强调。</li>
 *   <li><b>截断显式化</b>：超上限时截断并带标记，不静默丢内容。</li>
 *   <li><b>失败不反噬</b>：查库失败降级为"本轮没有画像"并打 WARN，绝不抛出 ——
 *       画像只是增强，不是对话的前置条件。</li>
 * </ol>
 *
 * @author zimo
 * @since 2026-10-26
 */
public class PersonaFragmentComposer {

    private static final Logger log = LoggerFactory.getLogger(PersonaFragmentComposer.class);

    /** 片段标题。与 L1 分块：便于单独评测、单独开关，也便于人眼区分两类记忆。 */
    public static final String PERSONA_TITLE = "## 用户画像（跨会话稳定记忆）";

    /**
     * 片段首行：标题 + 冲突声明。
     *
     * <p>与 L1 片段同样必须带冲突声明 —— 画像会过期（用户换了岗位、改了偏好），
     * 不加这条约束，模型会把旧画像当成命令执行，用户改也改不掉（PRD R3）。</p>
     */
    public static final String PERSONA_HEADER =
            PERSONA_TITLE + "（仅供参考，若与用户当前陈述冲突，以用户当前陈述为准）";

    /** 超上限时附加的截断标记（显式，便于在日志里区分"没有画像"与"画像被截断"）。 */
    public static final String TRUNCATED_MARK = "\n（画像过长已截断）";

    /** L0 留痕来源，与 L1 注入共用同一来源枚举值（都属于"上下文注入"）。 */
    public static final String SOURCE_CONTEXT_INJECTION = "context_injection";

    private final OltpMemoryRepository repository;
    private final PersonaInjection injection;
    /** 引擎总开关：关闭时与 L1 一起退场，不做半开状态。 */
    private final boolean engineEnabled;

    /**
     * @param repository    四层仓储（读 L3 画像、写 L0 留痕）；为 {@code null} 时本类整体不生效
     * @param injection     注入策略；为 {@code null} 时取 {@link PersonaInjection#DEFAULT}
     * @param engineEnabled 记忆感知引擎总开关
     */
    public PersonaFragmentComposer(
            OltpMemoryRepository repository, PersonaInjection injection, boolean engineEnabled) {
        this.repository = repository;
        this.injection = injection == null ? PersonaInjection.DEFAULT : injection;
        this.engineEnabled = engineEnabled;
    }

    /**
     * 组装本轮要注入的画像片段。
     *
     * <p>调用时机：与 L1 预召回同一处（{@code MemoryPromptMiddleware#onSystemPrompt} 首次调用），
     * 且必须在 {@code TraceCollector.begin()} 之后 —— 没有 traceId 就无法给留痕定位，
     * 因此 traceId 为空时<b>仍然返回片段，但不留痕</b>（画像是稳定内容，丢一次留痕比丢注入好）。</p>
     *
     * @param scope   隔离维度；不可用时返回 {@code null}
     * @param traceId 链路 ID；为空时只跳过留痕
     * @return 画像片段文本；未启用、无画像、查库失败时返回 {@code null}
     */
    public String compose(MemoryScope scope, String traceId) {
        if (!engineEnabled || !injection.enabled() || repository == null
                || scope == null || !scope.usable()) {
            return null;
        }
        List<L3Persona> personas = new ArrayList<>();
        try {
            for (String type : injection.types()) {
                L3Persona persona = repository.getPersona(scope.tenantId(), scope.userId(), type);
                if (persona != null && hasText(persona.content())) {
                    personas.add(persona);
                }
            }
        } catch (RuntimeException e) {
            // 与 L1 同一原则：记忆是增强能力，查不到画像的对话仍是可用的对话。
            // 打 WARN 而不是静默吞掉 —— 本项目已因静默失败踩过坑。
            log.warn("[memory-persona] 画像读取失败，本轮不注入画像 userId={}：{}",
                    scope.userId(), e.toString());
            return null;
        }
        if (personas.isEmpty()) {
            return null;
        }
        String fragment = render(personas);
        recordInjection(scope, traceId, fragment, personas.size());
        log.info("[memory-persona] traceId={} 注入画像 {} 条（类别 {}，{} 字符）",
                traceId, personas.size(), injection.types(), fragment.length());
        return fragment;
    }

    /**
     * 渲染画像列表：标题 + 每类一行，内容去重，超上限显式截断。
     *
     * <p>包可见便于单测直接钉住渲染规则（去重、顺序、截断），不必搭一个假仓储。</p>
     *
     * @param personas 已按策略顺序读到的画像（可能含重复内容）
     * @return 片段文本，永不为 {@code null}（调用方已保证列表非空）
     */
    String render(List<L3Persona> personas) {
        StringBuilder body = new StringBuilder(PERSONA_HEADER).append('\n');
        Set<String> seen = new LinkedHashSet<>();
        for (L3Persona persona : personas) {
            String content = oneLine(persona.content());
            if (content.isEmpty() || !seen.add(content)) {
                continue;
            }
            body.append("- [").append(oneLine(persona.personaType())).append("] ")
                    .append(content).append('\n');
        }
        String text = body.toString().stripTrailing();
        return text.length() <= injection.maxChars()
                ? text
                : text.substring(0, injection.maxChars()) + TRUNCATED_MARK;
    }

    /** 注入留痕：把「模型这一轮看到的画像」原样写进 L0，用于事后复盘。 */
    private void recordInjection(MemoryScope scope, String traceId, String fragment, int count) {
        if (traceId == null) {
            return;
        }
        try {
            repository.saveRawLog(L0RawLog.forInsert(traceId, scope.sessionId(), scope.userId(),
                    System.currentTimeMillis(), "system", fragment, null,
                    metaJson(count, fragment.length()), SOURCE_CONTEXT_INJECTION));
        } catch (RuntimeException e) {
            log.warn("[memory-persona] 画像留痕写入失败 traceId={}：{}", traceId, e.toString());
        }
    }

    /**
     * 留痕元数据：层标识、画像条数、字符数、类别。
     *
     * <p>刻意<b>不放时间戳</b>：L0 行本身已有 {@code ts} 列，重复记录会产生两个可能不一致的时间源。</p>
     */
    private String metaJson(int count, int chars) {
        return "{\"layer\":\"L3\",\"personas\":" + count
                + ",\"chars\":" + chars
                + ",\"types\":[\"" + String.join("\",\"", injection.types()) + "\"]}";
    }

    /** 单行化：画像内容可能含换行，直接拼进列表会破坏 Markdown 结构。 */
    private static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
