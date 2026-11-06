package com.zimo.agent.memory.engine;

import java.util.List;

/**
 * L3 用户画像注入策略（跨会话稳定画像，PRD §3.3 P4）。
 *
 * <p>所属模块：{@code agent-memory-core} 的 engine 层。本对象只描述「注入什么、注入多少」，
 * 不关心渲染与留痕 —— 那两件事在 {@link PersonaFragmentComposer}。</p>
 *
 * <h2>为什么默认读那三类画像</h2>
 * <p>{@code persona}（身份：我是谁、负责什么）、{@code preference}（偏好与长期指令）、
 * {@code habit}（习惯）是<b>跨会话稳定</b>的，放全文进系统提示词才划算。
 * <b>故意不读 {@code history}</b>：那是"某次任务做到哪一步"的流水，属于 L2 场景范畴，
 * 放进稳定画像会让每次对话都拖着一堆已完成任务的残影，且用户无法理解为什么模型总提旧任务。</p>
 *
 * <h2>为什么要 {@code maxChars} 而不是"全量注入"</h2>
 * <p>画像内容随使用时长单调增长，没有上限就意味着某天系统提示词会自己超长。截断是显式的
 * （末尾带标记），比等模型服务端报超长要好排查得多。</p>
 *
 * @param enabled  是否注入 L3 画像；{@code false} 时本轮连查库都不做
 * @param types    要读取的画像类别，按注入顺序排列；空/null 回落默认三类
 * @param maxChars 画像片段字符上限；{@code <= 0} 回落默认值
 * @author zimo
 * @since 2026-10-26
 */
public record PersonaInjection(boolean enabled, List<String> types, int maxChars) {

    /** 缺省是否注入：开（与 {@code agent-memory.engine.enabled} 同级，可单独止血）。 */
    public static final boolean DEFAULT_ENABLED = true;

    /** 缺省画像类别：身份 → 偏好/指令 → 习惯。 */
    public static final List<String> DEFAULT_TYPES = List.of("persona", "preference", "habit");

    /** 缺省字符上限：2000 字符约 1.2k token，足够容纳数十条画像事实。 */
    public static final int DEFAULT_MAX_CHARS = 2000;

    /** 缺省策略：开、三类、2000 字符。 */
    public static final PersonaInjection DEFAULT =
            new PersonaInjection(DEFAULT_ENABLED, DEFAULT_TYPES, DEFAULT_MAX_CHARS);

    /** 归一化：类别为空回落默认、上限非正回落默认，避免下游到处判空。 */
    public PersonaInjection {
        types = types == null || types.isEmpty() ? DEFAULT_TYPES : List.copyOf(types);
        maxChars = maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS;
    }
}
