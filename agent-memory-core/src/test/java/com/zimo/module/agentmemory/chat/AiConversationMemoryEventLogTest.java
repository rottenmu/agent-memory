package com.zimo.module.agentmemory.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.zimo.module.agentmemory.model.L0RawLog;
import com.zimo.module.agentmemory.storage.OltpMemoryRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AiConversationMemory L0 事件流测试：对话消息全量落事件 + 回放重建。
 */
class AiConversationMemoryEventLogTest {

    /** 内存版 L0 事件流（模拟 H2）。 */
    private static final class FakeOltp implements OltpMemoryRepository {
        final List<L0RawLog> logs = new ArrayList<>();

        @Override public void saveRawLog(L0RawLog log) { logs.add(log); }
        @Override public List<L0RawLog> listRawLogsByTrace(String traceId) {
            return logs.stream().filter(l -> l.traceId().equals(traceId)).toList();
        }
        @Override public List<L0RawLog> listRawLogsBySession(String sessionId) {
            return logs.stream().filter(l -> l.sessionId().equals(sessionId)).toList();
        }
        @Override public List<L0RawLog> listRawLogsPage(int offset, int limit) { return List.of(); }
        @Override public List<L0RawLog> listRawLogsSince(long afterId, int limit) { return List.of(); }
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
        @Override public List<L0RawLog> drillDownToRawLog(String traceId) {
            return logs.stream().filter(l -> l.traceId().equals(traceId)).toList();
        }
        @Override public long countL0Total() { return logs.size(); }
        @Override public long countL0Today() { return 0; }
    }

    @Test
    void appendTurnRecordsEventsAndRestoreReplays() {
        FakeOltp oltp = new FakeOltp();
        AiConversationMemory memory = new AiConversationMemory(512, null, oltp);

        memory.appendTurn("sess-1", "你好", "你好！有什么可以帮你？", 50);
        memory.appendTurn("sess-1", "帮我查周报", "好的，周报将在周五发送。", 50);

        // 事件流：2 轮 = 4 条消息
        assertThat(oltp.logs).hasSize(4);
        assertThat(oltp.logs.stream().map(L0RawLog::role))
                .containsExactly("user", "assistant", "user", "assistant");

        // 新实例（模拟重启）+ 回放重建
        AiConversationMemory restarted = new AiConversationMemory(512, null, oltp);
        List<AiChatMessage> restored = restarted.restore("sess-1");
        assertThat(restored).hasSize(4);
        assertThat(restored.get(0).content()).isEqualTo("你好");
        assertThat(restored.get(3).content()).isEqualTo("好的，周报将在周五发送。");
        assertThat(restarted.snapshot("sess-1")).hasSize(4);
    }

    @Test
    void noEventLogWhenNull() {
        AiConversationMemory memory = new AiConversationMemory(512, null, null);
        memory.appendTurn("sess-2", "hi", "hello", 50);
        assertThat(memory.restore("sess-2")).isEmpty();
    }
}
