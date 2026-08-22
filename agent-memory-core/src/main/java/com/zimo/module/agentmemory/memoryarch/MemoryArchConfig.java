package com.zimo.module.agentmemory.memoryarch;

/**
 * 记忆分层架构配置（SOUL 配置 / USER 档案）。
 *
 * @param id         主键（16 hex）
 * @param type       类型：SOUL（AI 身份） / USER（用户认知档案）
 * @param name       名称（USER=用户标识，SOUL=角色名）
 * @param summary    核心摘要（表格列表展示）
 * @param background 背景说明
 * @param content    完整内容描述
 * @param source     来源：自动（AI 提取）/ 手动（人工编辑）
 * @param version    版本号
 * @param updatedTs  更新时间戳（ms）
 */
public record MemoryArchConfig(
        String id,
        String type,
        String name,
        String summary,
        String background,
        String content,
        String source,
        int version,
        long updatedTs) {
}
