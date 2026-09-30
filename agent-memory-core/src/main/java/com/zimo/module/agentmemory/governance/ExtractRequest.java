package com.zimo.module.agentmemory.governance;

/**
 * 事实抽取入参（{@code POST /api/ai/memory/extract}）。
 *
 * <p>所属模块：module-agent-memory 记忆治理。用途：手动触发从 L0 原始对话抽取事实候选，
 * 服务于调试与历史回填（自动链路走异步侧，不需要该端点）。</p>
 *
 * <p><b>为什么不提供「只给 userId 就抽全部历史」的用法</b>：抽取必须有明确的输入范围
 * （{@code traceId} 或 {@code sessionId}），否则一次误调用会把整个租户的历史对话重抽一遍，
 * 而这些抽取结果会直接落进用户记忆。</p>
 *
 * @author zimo
 * @since 2026-09-15
 * @param tenantId  租户标识（数据隔离维度）；为空时按 {@code default} 处理
 * @param userId    用户标识；{@code persist=true} 时必填（否则会把记忆写到空用户名下）
 * @param traceId   链路标识；与 {@code sessionId} 至少一个非空，两者都为空时抛参数异常转 400
 * @param sessionId 会话标识；{@code traceId} 为空时按会话取 L0 日志
 * @param persist   是否落库；{@code null} 按 {@code false} 处理（**默认只回显候选不写库**），
 *                  回填场景需显式传 {@code true}
 */
public record ExtractRequest(
        String tenantId, String userId, String traceId, String sessionId, Boolean persist) {
}
