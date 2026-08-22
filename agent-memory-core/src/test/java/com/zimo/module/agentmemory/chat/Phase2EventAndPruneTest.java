package com.zimo.module.agentmemory.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.zimo.framework.common.ai.event.AiEventPublisher;
import com.zimo.framework.common.ai.event.ConversationTurnEvent;
import com.zimo.module.agentmemory.model.L0RawLog;
import com.zimo.module.agentmemory.storage.OltpMemoryRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 阶段2测试：会话事件发布 + 压缩剪枝。
 */
class Phase2EventAndPruneTest {

    private static final class FakeOltp implements OltpMemoryRepository {
        final List<L0RawLog> logs = new ArrayList<>();

        @Override public void saveRawLog(L0RawLog log) { logs.add(log); }
        @Override public List<L0RawLog> listRawLogsByTrace(String traceId) { return List.of(); }
        @Override public List<L0RawLog> listRawLogsBySession(String sessionId) {
            return logs.stream().filter(l -> l.sessionId().equals(sessionId)).toList();
        }
        @Override public List<L0RawLog> listRawLogsPage(int offset, int limit) { return List.of(); }
        @Override public List<L0RawLog> listRawLogsSince(long afterId, int limit) { return List.of(); }
        @Override public List<L0RawLog> drillDownToRawLog(String traceId) { return List.of(); }
        @Override public void saveAtomicMemory(com.zimo.module.agentmemory.model.L1AtomicMemory memory) { }
        @Override public void deleteAtomicMemory(String id) { }
        @Override public List<com.zimo.module.agentmemory.model.L1AtomicMemory> recallAtomicByType(String userId, String memoryType, int limit) { return List.of(); }
        @Override public List<com.zimo.module.agentmemory.model.L1AtomicMemory> recallAtomicBySession(String sessionId, int limit) { return List.of(); }
        @Override public List<com.zimo.module.agentmemory.model.L1AtomicMemory> listAtomicByTrace(String traceId) { return List.of(); }
        @Override public void saveSceneBlock(com.zimo.module.agentmemory.model.L2SceneBlock block) { }
        @Override public com.zimo.module.agentmemory.model.L2SceneBlock recallSceneBySession(String sessionId) { return null; }
        @Override public List<com.zimo.module.agentmemory.model.L2SceneBlock> listScenesBySession(String sessionId, int limit) { return List.of(); }
        @Override public void savePersona(com.zimo.module.agentmemory.model.L3Persona persona) { }
        @Override public com.zimo.module.agentmemory.model.L3Persona getPersona(String userId, String personaType) { return null; }
        @Override public void deletePersona(String userId, String personaType) { }
        @Override public List<com.zimo.module.agentmemory.model.L3Persona> listPersonas(String userId) { return List.of(); }
        @Override public long countL0Total() { return logs.size(); }
        @Override public long countL0Today() { return 0; }
    }

    @Test
    void publishesConversationTurnEvent() {
        FakeOltp oltp = new FakeOltp();
        List<Object> events = new ArrayList<>();
        AiEventPublisher bus = events::add;
        AiConversationMemory memory = new AiConversationMemory(512, null, oltp, bus);

        memory.appendTurn("s1", "你好", "你好！", 50);

        assertThat(events).hasSize(1);
        ConversationTurnEvent evt = (ConversationTurnEvent) events.get(0);
        assertThat(evt.sessionId()).isEqualTo("s1");
        assertThat(evt.userMessage()).isEqualTo("你好");
        assertThat(evt.assistantMessage()).isEqualTo("你好！");
    }

    @Test
    void forkCopiesSessionAndReplaysEvents() {
        FakeOltp oltp = new FakeOltp();
        AiConversationMemory memory = new AiConversationMemory(512, null, oltp);
        memory.appendTurn("src", "你好", "你好！", 50);
        memory.appendTurn("src", "帮我查周报", "好的。", 50);

        int copied = memory.fork("src", "child");
        assertThat(copied).isEqualTo(4);
        assertThat(memory.snapshot("child")).hasSize(4);
        // 事件流补记：源 4 条 + fork 补记 4 条 = 8
        assertThat(oltp.logs).hasSize(8);
        // fork 后子会话独立演进
        memory.appendTurn("child", "继续", "继续什么？", 50);
        assertThat(memory.snapshot("child")).hasSize(6);
    }

    @Test
    void titleFromFirstUserMessage() {
        FakeOltp oltp = new FakeOltp();
        AiConversationMemory memory = new AiConversationMemory(512, null, oltp);
        memory.appendTurn("t1", "请帮我整理周报并发送给经理", "好的。", 50);
        assertThat(memory.title("t1")).startsWith("请帮我整理周报");
        assertThat(memory.title("missing")).isEmpty();
    }

    @Test
    void compressionCandidatePrunesLongMessages() {
        FakeOltp oltp = new FakeOltp();
        AiConversationMemory memory = new AiConversationMemory(512, null, oltp);
        String huge = "x".repeat(6000);
        for (int i = 0; i < 12; i++) {
            memory.appendTurn("s2", huge, "回复" + i, 100);
        }
        // 触发压缩（trigger=10, recent=2）：候选中超长消息被丢弃/截断
        var candidate = memory.prepareCompression("s2", 10, 2);
        assertThat(candidate).isPresent();
        assertThat(candidate.get().messages().stream()
                .noneMatch(m -> m.content().length() > 5120)).isTrue();
    }
}
