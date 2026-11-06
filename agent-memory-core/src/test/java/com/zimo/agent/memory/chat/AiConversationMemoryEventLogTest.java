package com.zimo.agent.memory.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.zimo.agent.memory.model.L0RawLog;
import com.zimo.agent.memory.storage.FakeOltpMemoryRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AiConversationMemory L0 事件流测试：对话消息全量落事件 + 回放重建。
 */
class AiConversationMemoryEventLogTest {

    /** 内存版 L0 事件流（模拟 H2）；只覆写本测试用到的 L0 读取。 */
    private static final class FakeOltp extends FakeOltpMemoryRepository {
        final List<L0RawLog> logs = new ArrayList<>();
        boolean failWrites;

        @Override public void saveRawLog(L0RawLog log) {
            if (failWrites) {
                throw new IllegalStateException("L0 unavailable");
            }
            logs.add(log);
        }

        @Override public List<L0RawLog> listRawLogsByTrace(String traceId) {
            return logs.stream().filter(l -> l.traceId().equals(traceId)).toList();
        }

        @Override public List<L0RawLog> listRawLogsBySession(String sessionId) {
            return logs.stream().filter(l -> l.sessionId().equals(sessionId)).toList();
        }

        @Override public List<L0RawLog> drillDownToRawLog(String traceId) {
            return logs.stream().filter(l -> l.traceId().equals(traceId)).toList();
        }

        @Override public long countL0Total() { return logs.size(); }
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
        assertThat(oltp.logs.stream().map(L0RawLog::source))
                .containsExactly(
                        "user_message", "assistant_message", "user_message", "assistant_message");

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

    @Test
    void forkRecordsCopiedMessagesWithTheirSources() {
        FakeOltp oltp = new FakeOltp();
        AiConversationMemory memory = new AiConversationMemory(512, null, oltp);
        memory.appendTurn("source", "question", "answer", 50);

        assertThat(memory.fork("source", "child")).isEqualTo(2);
        assertThat(oltp.logs.stream().filter(log -> "child".equals(log.sessionId()))
                .map(L0RawLog::source))
                .containsExactly("user_message", "assistant_message");
    }

    @Test
    void trajectoryWriteFailureDoesNotPreventConversationAppend() {
        FakeOltp oltp = new FakeOltp();
        oltp.failWrites = true;
        AiConversationMemory memory = new AiConversationMemory(512, null, oltp);

        memory.appendTurn("sess-fail", "hi", "hello", 50);

        assertThat(memory.snapshot("sess-fail")).hasSize(2);
        assertThat(oltp.logs).isEmpty();
    }
}
