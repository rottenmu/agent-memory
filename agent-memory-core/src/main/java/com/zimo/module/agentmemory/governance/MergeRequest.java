package com.zimo.module.agentmemory.governance;

/**
 * 记忆合并入参（{@code POST /api/ai/memory/merge}）。
 *
 * <p>所属模块：module-agent-memory 记忆治理。用途：手动触发同用户同类别的近似条目归并，
 * 近似判定复用召回打分口径（PRD §3.1）。</p>
 *
 * @author zimo
 * @since 2026-09-15
 * @param tenantId 租户标识（数据隔离维度）；为空时按 {@code default} 处理
 * @param userId   用户标识；必填，合并只在单个用户范围内进行
 * @param dryRun   是否预演；{@code null} 按 {@code true} 处理。<b>默认预演</b>的理由：
 *                 合并会真实删除条目，只应在看过 {@code plan.groups} 之后才动手
 */
public record MergeRequest(String tenantId, String userId, Boolean dryRun) {
}
