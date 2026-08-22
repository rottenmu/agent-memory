package com.zimo.module.agentmemory.model;

/**
 * L3 用户画像：跨会话、跨场景的稳定用户记忆顶层。
 *
 * <p>会话启动时优先从 Caffeine 进程缓存加载（缓存未命中再查 H2），
 * 描述用户长期偏好、习惯与画像事实。{@code version} 支持画像版本演进。</p>
 *
 * @param id         画像记录 ID
 * @param userId     用户标识
 * @param personaType 画像类别：persona / preference / habit / history
 * @param content    画像内容
 * @param version    版本号（每次更新 +1）
 * @param updatedTs  更新时间戳（毫秒）
 */
public record L3Persona(
        String id,
        String userId,
        String personaType,
        String content,
        int version,
        long updatedTs) {
}
