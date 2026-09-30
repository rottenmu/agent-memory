package com.zimo.module.agentmemory.governance;

/**
 * 批量写入的单条入参。
 *
 * <p>所属模块：module-agent-memory 记忆治理。用途：{@code POST /api/ai/memory/batch}
 * 的 items 元素，也可供脚本回填直接构造。</p>
 *
 * <p><b>为什么用统一结构承载三种写入目标</b>：user / global / session 三档的差异只在
 * 「谁承载键」——用户记忆用 {@code userId}、全局记忆用 {@code key}、会话变量用
 * {@code sessionId} + {@code key}。若为每档单独建一个入参类型，批量接口就得按 target
 * 分成三个数组，调用方反而更难用；用一个结构 + {@link #target()} 分发更贴近实际用法。</p>
 *
 * <p><b>非法目标不静默降级</b>：{@code target} 写错（例如 {@code "gloabl"}）时
 * <b>不</b>回落到默认值，而是由 {@link MemoryManager} 逐条拒绝并给出原因。理由是
 * 「拼错的 target 被当成用户记忆写进去」会把数据放进错误的桶里，且外表看是成功的 ——
 * 这正是 PRD 反复强调要消灭的静默错误。</p>
 *
 * @author zimo
 * @since 2026-09-15
 * @param target     写入目标：{@code user} / {@code global} / {@code session}；为空时由批量层按 {@code user} 处理，非法值被拒绝
 * @param userId     用户标识，{@code target=user} 时必填
 * @param sessionId  会话标识，{@code target=session} 时必填
 * @param key        变量名 / 记忆键，{@code target=global|session} 时必填
 * @param category   记忆类别，{@code target=user} 时使用；为空按 {@code custom} 处理
 * @param content    记忆内容（原文，脱敏由服务层统一执行）；不可为空
 * @param importance 显式重要性 0~1；{@code null} 表示自动评估
 */
public record WriteItem(
        String target,
        String userId,
        String sessionId,
        String key,
        String category,
        String content,
        Double importance) {

    /** 批量写入目标：用户长期记忆。 */
    public static final String TARGET_USER = "user";

    /** 批量写入目标：全局（租户级）记忆。 */
    public static final String TARGET_GLOBAL = "global";

    /** 批量写入目标：会话变量。 */
    public static final String TARGET_SESSION = "session";

    /** 归一化：目标统一小写去空白；**不做默认值回落**（原因见类注释）。 */
    public WriteItem {
        target = target == null ? "" : target.trim().toLowerCase();
    }

    /**
     * 构造一条用户长期记忆写入项（脚本与测试的便捷入口）。
     *
     * @param userId     用户标识
     * @param category   记忆类别
     * @param content    记忆内容
     * @param importance 显式重要性；{@code null} 表示自动评估
     * @return 写入项
     */
    public static WriteItem user(
            String userId, String category, String content, Double importance) {
        return new WriteItem(TARGET_USER, userId, null, null, category, content, importance);
    }

    /**
     * 构造一条全局记忆写入项。
     *
     * @param key     记忆键
     * @param content 记忆内容
     * @return 写入项
     */
    public static WriteItem global(String key, String content) {
        return new WriteItem(TARGET_GLOBAL, null, null, key, null, content, null);
    }

    /**
     * 构造一条会话变量写入项。
     *
     * @param sessionId 会话标识
     * @param key       变量名
     * @param content   变量值
     * @return 写入项
     */
    public static WriteItem session(String sessionId, String key, String content) {
        return new WriteItem(TARGET_SESSION, null, sessionId, key, null, content, null);
    }

    /**
     * 生效的写入目标。
     *
     * <p>{@code target} 为空视为 {@code user}（批量接口的主要场景是用户长期记忆回填），
     * 其余取值原样返回，由调用方判定合法性。</p>
     *
     * @return 归一化后的目标值，可能为非法值
     */
    public String effectiveTarget() {
        return target == null || target.isBlank() ? TARGET_USER : target;
    }

    /**
     * 目标值是否为受支持的三种之一。
     *
     * @return 受支持时返回 {@code true}
     */
    public boolean targetSupported() {
        String value = effectiveTarget();
        return TARGET_USER.equals(value)
                || TARGET_GLOBAL.equals(value)
                || TARGET_SESSION.equals(value);
    }
}
