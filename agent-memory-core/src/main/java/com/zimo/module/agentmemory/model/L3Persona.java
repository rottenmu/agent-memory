package com.zimo.module.agentmemory.model;

/**
 * L3 用户画像：跨会话、跨场景的稳定用户记忆顶层。
 *
 * <p>会话启动时优先从 Caffeine 进程缓存加载（缓存未命中再查 H2），
 * 描述用户长期偏好、习惯与画像事实。{@code version} 支持画像版本演进。</p>
 *
 * <p>{@code tenantId} 与 {@code userId} 共同构成查询的**强制双条件**：曾因缺少租户列，
 * 同一 {@code userId} 在不同租户看到同一份画像。缓存键也必须带上租户。</p>
 *
 * @param id           画像记录 ID
 * @param userId       用户标识
 * @param personaType  画像类别：persona / preference / habit / history
 * @param content      画像内容
 * @param version      版本号（每次更新 +1）
 * @param updatedTs    更新时间戳（毫秒）
 * @param tenantId     租户标识（不可为空，空值归一为 {@link #DEFAULT_TENANT}）
 * @param importance   重要性 0~1（越界会被夹到区间内）
 * @param accessCount  被召回次数（负数归一为 0）
 * @param lastAccessAt 最近一次被召回时间（毫秒，从未被召回为 {@code null}）
 * @param createdAt    创建时间戳（毫秒；旧数据回填为 {@code updatedTs}）
 */
public record L3Persona(
        String id,
        String userId,
        String personaType,
        String content,
        int version,
        long updatedTs,
        String tenantId,
        double importance,
        int accessCount,
        Long lastAccessAt,
        long createdAt) {

    /** 缺省租户：既有数据与未显式指定租户的调用统一落这里。 */
    public static final String DEFAULT_TENANT = "default";
    /** 默认重要性（未经重要性评估时的中性取值）。 */
    public static final double DEFAULT_IMPORTANCE = 0.5;

    /**
     * 归一化非法取值：空租户落到缺省值，重要性夹到 0~1，负数召回次数归零。
     * {@code createdAt} 为 0 时回填 {@code updatedTs}（旧库补列后的数据形态）。
     */
    public L3Persona {
        tenantId = tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT : tenantId;
        accessCount = Math.max(0, accessCount);
        importance = Double.isNaN(importance) || importance < 0.0 ? 0.0
                : Math.min(importance, 1.0);
        createdAt = createdAt > 0 ? createdAt : updatedTs;
    }

    /**
     * 新建一条画像：{@code accessCount} / {@code lastAccessAt} 取默认值，
     * {@code createdAt} 与 {@code updatedTs} 同为一刻。
     *
     * @param id          画像记录 ID
     * @param userId      用户标识
     * @param personaType 画像类别
     * @param content     画像内容
     * @param version     版本号
     * @param ts          时间戳（毫秒）
     * @param tenantId    租户标识
     * @return 新画像实例
     */
    public static L3Persona create(
            String id, String userId, String personaType, String content,
            int version, long ts, String tenantId) {
        return create(id, userId, personaType, content, version, ts, tenantId, DEFAULT_IMPORTANCE);
    }

    /**
     * 新建一条画像并指定重要性。
     *
     * <p>重要性由 {@code ImportanceScorer} 评估或调用方显式给出（画像是最主要的高重要性来源）；
     * {@code accessCount} / {@code lastAccessAt} 取默认值，{@code createdAt} 与 {@code updatedTs} 同刻。</p>
     *
     * @param id          画像记录 ID
     * @param userId      用户标识
     * @param personaType 画像类别
     * @param content     画像内容
     * @param version     版本号
     * @param ts          时间戳（毫秒）
     * @param tenantId    租户标识
     * @param importance  重要性 0~1
     * @return 新画像实例
     */
    public static L3Persona create(
            String id, String userId, String personaType, String content,
            int version, long ts, String tenantId, double importance) {
        return new L3Persona(id, userId, personaType, content, version, ts, tenantId,
                importance, 0, null, ts);
    }
}
