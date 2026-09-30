package com.zimo.module.agentmemory.governance;

import java.util.List;

/**
 * 批量读取入参（{@code POST /api/ai/memory/batch/query}）。
 *
 * <p>所属模块：module-agent-memory 记忆治理。用途：按 id 集合批量拉取用户长期记忆，
 * 供删除前校验与导出后对账使用。</p>
 *
 * @author zimo
 * @since 2026-09-15
 * @param tenantId 租户标识（数据隔离维度）；为空时按 {@code default} 处理
 * @param userId   用户标识；必填，用于把读取范围限制在单个用户内（防止越权读他人记忆）
 * @param ids      记忆主键集合；重复项自动去重，去重后上限为
 *                 {@link MemoryManager#MAX_BATCH_QUERY_IDS} 个，超限抛参数异常转 400
 */
public record BatchQueryRequest(String tenantId, String userId, List<String> ids) {
}
