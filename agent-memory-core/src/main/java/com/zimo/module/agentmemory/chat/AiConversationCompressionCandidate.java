package com.zimo.module.agentmemory.chat;

import java.util.List;

/**
 * 智能体会话上下文压缩候选项，携带生成摘要时所需的固定会话快照。
 *
 * @param sessionId 会话唯一标识，不能为空白
 * @param revision 候选项创建时的会话版本，用于避免并发写入覆盖
 * @param existingSummary 压缩前已有的摘要，可为 {@code null}
 * @param messages 待压缩的较早原始消息，不可变且不包含保留的最近消息
 */
public record AiConversationCompressionCandidate(
        String sessionId,
        long revision,
        String existingSummary,
        List<AiChatMessage> messages) {

    public AiConversationCompressionCandidate {
        messages = List.copyOf(messages);
    }
}