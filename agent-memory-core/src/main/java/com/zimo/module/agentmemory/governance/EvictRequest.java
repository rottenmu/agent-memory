package com.zimo.module.agentmemory.governance;

/**
 * 遗忘淘汰入参（{@code POST /api/ai/memory/evict}）。
 *
 * <p>所属模块：module-agent-memory 记忆治理。用途：手动触发低分且长期未访问条目的淘汰，
 * 分数口径与召回完全一致（PRD §7.3）。</p>
 *
 * @author zimo
 * @since 2026-09-15
 * @param tenantId  租户标识（数据隔离维度）；为空时按 {@code default} 处理；
 *                  淘汰按租户执行（用户维度不参与，避免逐用户调度）
 * @param dryRun    是否预演；{@code null} 按 {@code true} 处理。<b>默认预演</b>的理由：
 *                  淘汰是删除动作，且阈值配错时会成片删数据，先看清单再执行
 * @param threshold 分数阈值（0~1）；{@code null} 表示用 {@link MemoryEvictor#DEFAULT_THRESHOLD}；
 *                  传得过低会让召回已不注入的条目长期留存，过高会误删仍可用的记忆
 */
public record EvictRequest(String tenantId, Boolean dryRun, Double threshold) {
}
