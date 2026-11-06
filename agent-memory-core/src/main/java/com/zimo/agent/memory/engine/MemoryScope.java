package com.zimo.agent.memory.engine;

/**
 * 记忆感知的隔离维度（PRD §9.1）。
 *
 * <p>所属模块：module-agent-memory 记忆感知引擎。前三个字段都是<b>隔离键</b>，不是可选装饰：
 * 记忆的每一次读写都必须同时带租户与用户，否则会出现「同一 {@code userId} 在不同租户看到
 * 同一份数据」（缺陷 D8b）。把维度收成一个对象传，比散成三个字符串参数更难漏传。</p>
 *
 * <p><b>{@code agentType} 的定位与前三个不同</b>：它是<b>召回策略的输入</b>，不是隔离键 ——
 * 计划模式（{@code plan}）要额外召回程序性记忆，对话模式不需要（M4-2a，标准 11）。
 * 二者混为一谈会出错：若把它当隔离键，就会出现「同一用户在 plan 下落下的记忆
 * 在 conversation 下看不见」这种没人要求的行为。因此本字段只影响<b>召回范围的宽窄</b>，
 * 不参与任何过滤条件的拼接。缺失（{@code null}）等价于「按默认策略召回」，
 * 与既有行为一致 —— 这是保证 M4 不改变存量行为的关键。</p>
 *
 * @param tenantId  租户标识
 * @param userId    用户标识
 * @param sessionId 会话标识（用于注入留痕与审计，不参与隔离过滤）
 * @param agentType 智能体类型（{@code plan} / {@code conversation} 等）；{@code null} 表示不限
 */
public record MemoryScope(String tenantId, String userId, String sessionId, String agentType) {

    /** Plan-and-Execute 类型标识：与 {@code AiAgentProfile.TYPE_PLAN} 取值一致。 */
    public static final String TYPE_PLAN = "plan";

    /** 是否可用于召回：租户与用户齐备才算（会话可以缺，它只影响留痕归属）。 */
    public boolean usable() {
        return hasText(tenantId) && hasText(userId);
    }

    /**
     * 是否应按计划模式召回（额外拉取程序性记忆）。
     *
     * <p>判据是「类型确为 plan」，不是「非空」—— 否则任何带类型的调用都会拿到程序性记忆，
     * 标准 11 的隔离维度当场失效。</p>
     *
     * @return {@code agentType} 为 {@code plan} 时返回 {@code true}
     */
    public boolean planMode() {
        return TYPE_PLAN.equalsIgnoreCase(agentType == null ? null : agentType.trim());
    }

    /** 便捷构造（参数顺序即字段顺序，避免调用点把 tenant 与 user 写反）；不限智能体类型。 */
    public static MemoryScope of(String tenantId, String userId, String sessionId) {
        return new MemoryScope(tenantId, userId, sessionId, null);
    }

    /** 便捷构造：显式指定智能体类型（M4-2a 的调用点用这个）。 */
    public static MemoryScope of(String tenantId, String userId, String sessionId, String agentType) {
        return new MemoryScope(tenantId, userId, sessionId, agentType);
    }

    /**
     * 派生一个带智能体类型的新 scope。
     *
     * <p>用于「先在路由阶段建 scope、后在确定 agentType 后补齐」的场景，
     * 避免为补一个字段而重写整串隔离键（重写就多一次把 tenant/user 写反的机会）。</p>
     *
     * @param type 智能体类型；为空时返回 {@code this}，不制造「把已有类型清空」的意外
     * @return 补上类型的新 scope；本对象已带类型时以本对象为准
     */
    public MemoryScope withAgentType(String type) {
        if (!hasText(type) || hasText(agentType)) {
            return this;
        }
        return new MemoryScope(tenantId, userId, sessionId, type);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
