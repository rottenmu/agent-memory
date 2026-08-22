package com.zimo.module.agentmemory.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zimo.framework.common.ai.event.AiEventPublisher;
import com.zimo.framework.common.ai.event.ConversationTurnEvent;
import com.zimo.framework.common.storage.FileStorageService;
import com.zimo.module.agentmemory.model.L0RawLog;
import com.zimo.module.agentmemory.storage.OltpMemoryRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 智能体会话记忆，保存原始消息与一条可替换的上下文摘要。
 *
 * <p>两种存储模式：</p>
 * <ul>
 *   <li><b>内存模式（默认）</b>：进程内 {@code LinkedHashMap}，会话上限由
 *       {@code maxSessions} 控制（超限淘汰最旧）；</li>
 *   <li><b>共享模式</b>：构造时传入非空 {@link FileStorageService}（RocksDB），
 *       会话消息/摘要按 {@code convo/{sessionId}} 持久化，多实例部署下会话记忆
 *       跨实例共享；单实例内由 revision 版本号做乐观并发控制。</li>
 * </ul>
 */
public class AiConversationMemory {
    private static final Logger log = LoggerFactory.getLogger(AiConversationMemory.class);

    private static final String SUMMARY_PREFIX = "此前对话摘要，仅作事实与约束参考：";
    private static final String SHARED_PREFIX = "convo";

    private final Map<String, SessionMemory> sessions = new LinkedHashMap<>();
    private final int maxSessions;
    private final FileStorageService sharedStorage;
    private final ObjectMapper objectMapper;
    /** L0 事件流（可空）：非空时 appendTurn 全量记录对话消息（role=user/assistant），支持跨重启回放。 */
    private final OltpMemoryRepository eventLog;
    /** 事件总线（可空）：非空时发布 ConversationTurnEvent（会话域）。 */
    private final AiEventPublisher eventPublisher;

    /** 使用默认会话上限 {@code 512}（内存模式）。 */
    public AiConversationMemory() {
        this(512, null);
    }

    /**
     * @param maxSessions 内存模式最大会话数；超限时淘汰最旧会话
     */
    public AiConversationMemory(int maxSessions) {
        this(maxSessions, null);
    }

    /**
     * @param maxSessions 内存模式最大会话数；超限时淘汰最旧会话
     * @param sharedStorage 非空时启用共享模式（RocksDB 持久化，跨实例共享会话记忆）
     */
    public AiConversationMemory(int maxSessions, FileStorageService sharedStorage) {
        this(maxSessions, sharedStorage, null, null);
    }

    /**
     * @param maxSessions 内存模式最大会话数；超限时淘汰最旧会话
     * @param sharedStorage 非空时启用共享模式（RocksDB 持久化，跨实例共享会话记忆）
     * @param eventLog L0 事件流（非空时对话消息全量落 L0，可回放）
     */
    public AiConversationMemory(int maxSessions, FileStorageService sharedStorage, OltpMemoryRepository eventLog) {
        this(maxSessions, sharedStorage, eventLog, null);
    }

    /**
     * 全量构造。
     *
     * @param eventPublisher 事件总线（可空）；发布会话事件
     */
    public AiConversationMemory(int maxSessions, FileStorageService sharedStorage,
                                OltpMemoryRepository eventLog, AiEventPublisher eventPublisher) {
        this.maxSessions = maxSessions > 0 ? maxSessions : 512;
        this.sharedStorage = sharedStorage;
        this.objectMapper = new ObjectMapper();
        this.eventLog = eventLog;
        this.eventPublisher = eventPublisher;
    }

    /** 是否共享模式。 */
    public boolean isShared() {
        return sharedStorage != null;
    }

    /**
     * 获取指定会话的请求快照，摘要始终作为第一条 system 消息返回。
     *
     * @param sessionId 会话唯一标识；空白值返回空列表
     * @return 不可修改的会话消息快照；无会话或无消息时返回空列表
     */
    public synchronized List<AiChatMessage> snapshot(String sessionId) {
        if (!hasText(sessionId)) {
            return List.of();
        }
        SessionMemory session = loadSession(sessionId);
        if (session == null || (session.summary == null && session.messages.isEmpty())) {
            return List.of();
        }
        List<AiChatMessage> snapshot = new ArrayList<>();
        if (hasText(session.summary)) {
            snapshot.add(new AiChatMessage("system", SUMMARY_PREFIX + session.summary));
        }
        snapshot.addAll(session.messages);
        return List.copyOf(snapshot);
    }

    /**
     * 向会话追加一轮成功问答，并按原始消息数量上限从队首截断。
     *
     * @param sessionId 会话唯一标识；空白值不写入
     * @param userMessage 用户消息内容
     * @param assistantMessage 智能体回复内容
     * @param maxMessages 原始消息最大条数；非正数不写入
     */
    public synchronized void appendTurn(String sessionId, String userMessage, String assistantMessage, int maxMessages) {
        if (!hasText(sessionId) || maxMessages <= 0) {
            return;
        }
        SessionMemory session = loadSession(sessionId);
        if (session == null) {
            session = new SessionMemory();
        }
        session.messages.addLast(new AiChatMessage("user", userMessage));
        session.messages.addLast(new AiChatMessage("assistant", assistantMessage));
        trimMessages(session.messages, maxMessages);
        session.revision++;
        storeSession(sessionId, session);
        recordTurnEvent(sessionId, userMessage, assistantMessage);
    }

    /**
     * 创建一次上下文压缩候选项，不会修改当前会话记忆。
     *
     * @param sessionId 会话唯一标识；空白值返回空结果
     * @param triggerMessages 触发压缩的原始消息数量阈值，必须大于保留数量
     * @param recentMessages 压缩后保留的最近原始消息数量，必须为正数
     * @return 待压缩消息与会话版本快照；不满足条件时返回 {@link Optional#empty()}
     */
    public synchronized Optional<AiConversationCompressionCandidate> prepareCompression(
            String sessionId, int triggerMessages, int recentMessages) {
        if (!hasText(sessionId) || triggerMessages <= recentMessages || recentMessages <= 0) {
            return Optional.empty();
        }
        SessionMemory session = loadSession(sessionId);
        if (session == null || session.messages.size() < triggerMessages) {
            return Optional.empty();
        }
        int compressionCount = session.messages.size() - recentMessages;
        if (compressionCount <= 0) {
            return Optional.empty();
        }
        return Optional.of(new AiConversationCompressionCandidate(
                sessionId, session.revision, session.summary,
                pruneForCompression(firstMessages(session.messages, compressionCount))));
    }

    /**
     * 原子应用有效的摘要结果，候选项过期或摘要空白时保持会话记忆不变。
     *
     * @param candidate 压缩前生成的会话候选项，不能为空
     * @param summary 模型生成的摘要内容，必须非空白
     * @param maxMessages 原始消息最大条数；正数时继续执行队首截断
     * @return 成功替换摘要并移除候选消息时返回 {@code true}，否则返回 {@code false}
     */
    public synchronized boolean applyCompression(
            AiConversationCompressionCandidate candidate, String summary, int maxMessages) {
        if (candidate == null || !hasText(summary)) {
            return false;
        }
        SessionMemory session = loadSession(candidate.sessionId());
        if (session == null || session.revision != candidate.revision()) {
            return false;
        }
        int messageCount = candidate.messages().size();
        if (messageCount <= 0 || session.messages.size() < messageCount
                || !matchesCandidateMessages(session.messages, candidate.messages())) {
            return false;
        }
        session.summary = summary.trim();
        removeFirstMessages(session.messages, messageCount);
        if (maxMessages > 0) {
            trimMessages(session.messages, maxMessages);
        }
        session.revision++;
        storeSession(candidate.sessionId(), session);
        return true;
    }

    /* ---------------- 存储后端 ---------------- */

    private SessionMemory loadSession(String sessionId) {
        if (sharedStorage == null) {
            return sessions.get(sessionId);
        }
        byte[] raw = sharedStorage.get(SHARED_PREFIX + "/" + safe(sessionId));
        if (raw == null) {
            return null;
        }
        try {
            SharedSession shared = objectMapper.readValue(raw, SharedSession.class);
            SessionMemory session = new SessionMemory();
            session.summary = shared.summary();
            session.revision = shared.revision();
            session.messages.addAll(shared.messages() == null ? List.of() : shared.messages());
            return session;
        } catch (Exception e) {
            log.warn("共享会话记忆读取失败 sessionId={}", sessionId, e);
            return null;
        }
    }

    /** L0 事件流：记录本轮 user/assistant 消息（全量、可回放；会话事件域）。 */
    private void recordTurnEvent(String sessionId, String userMessage, String assistantMessage) {
        if (eventLog == null || !hasText(sessionId)) {
            return;
        }
        long ts = System.currentTimeMillis();
        String traceId = "convo-" + cn.hutool.core.util.IdUtil.fastSimpleUUID().substring(0, 12);
        if (hasText(userMessage)) {
            eventLog.saveRawLog(L0RawLog.forInsert(traceId, sessionId, null, ts, "user", userMessage, null, null));
        }
        if (hasText(assistantMessage)) {
            eventLog.saveRawLog(L0RawLog.forInsert(traceId, sessionId, null, ts, "assistant", assistantMessage, null, null));
        }
        if (eventPublisher != null) {
            eventPublisher.publish(new ConversationTurnEvent(sessionId, userMessage, assistantMessage, ts));
        }
    }

    /**
     * 会话 fork（对应 dsh ctx.sessions.fork）：将源会话的消息与摘要复制到新会话。
     * 新会话以 {@code childSessionId} 独立继续演进；事件流启用时源消息会作为新会话事件补记。
     *
     * @param sourceSessionId 源会话
     * @param childSessionId  子会话（须非空且不同于源）
     * @return fork 的消息条数；源会话不存在返回 0
     */
    public synchronized int fork(String sourceSessionId, String childSessionId) {
        if (!hasText(sourceSessionId) || !hasText(childSessionId)
                || sourceSessionId.equals(childSessionId)) {
            return 0;
        }
        SessionMemory source = sessions.get(sourceSessionId);
        if (source == null) {
            return 0;
        }
        List<AiChatMessage> copy = new ArrayList<>(source.messages);
        SessionMemory child = new SessionMemory(copy);
        child.summary = source.summary;
        sessions.put(childSessionId, child);
        if (eventLog != null) {
            long ts = System.currentTimeMillis();
            String traceId = "convo-" + cn.hutool.core.util.IdUtil.fastSimpleUUID().substring(0, 12);
            for (AiChatMessage m : copy) {
                eventLog.saveRawLog(L0RawLog.forInsert(
                        traceId, childSessionId, null, ts, m.role(), m.content(), null, null));
            }
        }
        return copy.size();
    }

    /**
     * 会话标题（对应 dsh ctx.sessionTitle）：取首条用户消息前 20 字；无消息返回空。
     */
    public synchronized String title(String sessionId) {
        SessionMemory session = sessions.get(sessionId);
        if (session == null || session.messages.isEmpty()) {
            return "";
        }
        for (AiChatMessage m : session.messages) {
            if ("user".equals(m.role()) && hasText(m.content())) {
                String t = m.content().replace("\n", " ").trim();
                return t.length() > 20 ? t.substring(0, 20) + "…" : t;
            }
        }
        return "";
    }

    /**
     * 从 L0 事件流回放会话消息（role=user/assistant），重建内存会话。
     * 无事件或事件流未启用时返回空。
     *
     * @param sessionId 会话 ID
     * @return 回放的消息列表（时间正序）
     */
    public synchronized List<AiChatMessage> restore(String sessionId) {
        if (eventLog == null || !hasText(sessionId)) {
            return List.of();
        }
        List<AiChatMessage> restored = new ArrayList<>();
        for (L0RawLog row : eventLog.listRawLogsBySession(sessionId)) {
            if ("user".equals(row.role()) || "assistant".equals(row.role())) {
                restored.add(new AiChatMessage(row.role(), row.content()));
            }
        }
        if (!restored.isEmpty()) {
            sessions.put(sessionId, new SessionMemory(restored));
        }
        return restored;
    }

    private void storeSession(String sessionId, SessionMemory session) {
        if (sharedStorage == null) {
            sessions.put(sessionId, session);
            trimSessionCount();
            return;
        }
        try {
            SharedSession shared = new SharedSession(
                    session.summary,
                    session.revision,
                    new ArrayList<>(session.messages));
            sharedStorage.store(SHARED_PREFIX + "/" + safe(sessionId),
                    objectMapper.writeValueAsBytes(shared));
        } catch (Exception e) {
            log.warn("共享会话记忆写入失败 sessionId={}", sessionId, e);
        }
    }

    /** 共享模式下的会话记忆持久化快照。 */
    private record SharedSession(String summary, long revision, List<AiChatMessage> messages) {
    }

    private void trimSessionCount() {
        while (sessions.size() > maxSessions) {
            String oldest = new ArrayList<>(sessions.keySet()).get(0);
            sessions.remove(oldest);
        }
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * 压缩前剪枝（对应 dsh compaction 第一步）：丢弃低价值超长消息并截断单条超长内容，
     * 降低摘要输入规模。
     */
    private static List<AiChatMessage> pruneForCompression(List<AiChatMessage> messages) {
        final int maxSingle = 2048;
        List<AiChatMessage> pruned = new ArrayList<>();
        for (AiChatMessage m : messages) {
            String content = m.content();
            if (content != null && content.length() > 5120) {
                continue;
            }
            if (content != null && content.length() > maxSingle) {
                pruned.add(new AiChatMessage(m.role(), content.substring(0, maxSingle) + "…[截断]"));
            } else {
                pruned.add(m);
            }
        }
        return pruned;
    }

    private static List<AiChatMessage> firstMessages(Deque<AiChatMessage> messages, int count) {
        List<AiChatMessage> result = new ArrayList<>(count);
        int index = 0;
        for (AiChatMessage message : messages) {
            if (index++ >= count) {
                break;
            }
            result.add(message);
        }
        return result;
    }

    private static boolean matchesCandidateMessages(
            Deque<AiChatMessage> messages, List<AiChatMessage> candidateMessages) {
        int index = 0;
        for (AiChatMessage message : messages) {
            if (index == candidateMessages.size()) {
                return true;
            }
            if (!message.equals(candidateMessages.get(index++))) {
                return false;
            }
        }
        return index == candidateMessages.size();
    }

    private static void removeFirstMessages(Deque<AiChatMessage> messages, int count) {
        for (int index = 0; index < count; index++) {
            messages.removeFirst();
        }
    }

    private static void trimMessages(Deque<AiChatMessage> messages, int maxMessages) {
        while (messages.size() > maxMessages) {
            messages.removeFirst();
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static class SessionMemory {
        private String summary;
        private final Deque<AiChatMessage> messages = new ArrayDeque<>();
        private long revision;

        SessionMemory() {
        }

        SessionMemory(List<AiChatMessage> initial) {
            messages.addAll(initial);
        }
    }
}
