package com.zimo.module.agentmemory.governance;

import java.util.List;

/**
 * 召回入参（{@code POST /api/ai/memory/recall}）。
 *
 * <p>所属模块：module-agent-memory 记忆治理。用途：按查询文本召回某租户某用户的长期记忆，
 * 打分口径见 PRD §3.2（S1 混合打分）。</p>
 *
 * @author zimo
 * @since 2026-09-15
 * @param tenantId     租户标识（数据隔离维度）；为空时按 {@code default} 处理
 * @param userId       用户标识；必填，召回范围限定在该用户内
 * @param query        查询文本；为空表示只按新鲜度与重要性排序（不报错，便于「看看这个用户都记了什么」的用法）
 * @param types        类别白名单（如 {@code custom} / {@code preference}）；为空或 {@code null} 表示不限类别
 * @param excludeTypes 类别黑名单（M4-2a）；为空或 {@code null} 表示不排除任何类别。
 *                     与 {@code types} 同时生效（先白后黑）
 * @param topK         返回条数上限；{@code null} 或非正数表示用配置默认值，超过
 *                     {@link MemoryManager#MAX_TOP_K} 时被夹到上限而不是报错
 */
public record RecallRequest(
        String tenantId, String userId, String query, List<String> types,
        List<String> excludeTypes, Integer topK) {
}
