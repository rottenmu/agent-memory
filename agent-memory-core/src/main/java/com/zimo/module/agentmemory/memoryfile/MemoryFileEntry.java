package com.zimo.module.agentmemory.memoryfile;

/**
 * 文件记忆条目：主题（section）下的一条记忆。
 *
 * @param topic   主题（MEMORY.md 的 {@code ## 主题} 标题；OpenClaw/Claude Code 兼容）
 * @param content 条目内容（MEMORY.md 的 {@code - 内容} 列表项）
 */
public record MemoryFileEntry(String topic, String content) {
}
