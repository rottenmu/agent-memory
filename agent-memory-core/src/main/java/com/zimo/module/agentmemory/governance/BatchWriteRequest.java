package com.zimo.module.agentmemory.governance;

import java.util.List;

/**
 * 批量写入入参（{@code POST /api/ai/memory/batch}）。
 *
 * <p>所属模块：module-agent-memory 记忆治理。用途：批量写入接口的请求体，
 * 每条的字段语义见 {@link WriteItem}。</p>
 *
 * @author zimo
 * @since 2026-09-15
 * @param tenantId 租户标识（数据隔离维度）；为空时按 {@code default} 处理，与模块既有端点口径一致
 * @param items    写入项列表；条数上限为 {@link MemoryManager#MAX_BATCH_ITEMS}，
 *                 超限由门面抛参数异常并由全局异常处理器转 400（超限即整批拒绝，
 *                 避免「静默截断前 100 条」这种看不出丢数据的失败）
 */
public record BatchWriteRequest(String tenantId, List<WriteItem> items) {
}
