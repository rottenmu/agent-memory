package com.zimo.agent.memory.memory;

import com.zimo.agent.memory.model.L0RawLog;
import com.zimo.agent.memory.storage.FakeOltpMemoryRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TrajectoryRecorder 六类事件采集与仅追加语义验证。
 *
 * <p>覆盖：系统提示词 / 思维链 / 工具调用与结果 / 子 Agent / 上下文注入
 * 全部写入 L0 且 source 分类正确；空白内容跳过；异常不影响主流程。</p>
 */
class TrajectoryRecorderTest {

    /** 捕获落库日志的内存仓储 mock：只关心 L0 追加，其余行为继承空实现。 */
    private static final class CapturingRepo extends FakeOltpMemoryRepository {
        final List<L0RawLog> logs = new ArrayList<>();

        @Override public void saveRawLog(L0RawLog log) { logs.add(log); }
        @Override public long countL0Total() { return logs.size(); }
    }

    @Test
    @DisplayName("六类 Trajectory 事件全部落库且 source 分类正确")
    void recordsAllSources() {
        CapturingRepo repo = new CapturingRepo();
        TrajectoryRecorder recorder = new TrajectoryRecorder(repo);

        recorder.recordSystemPrompt("s1", "u1", "trace-1", "你是系统助手");
        recorder.recordChainOfThought("s1", "u1", "trace-1", "用户想查订单", 120);
        recorder.recordToolCall("s1", "u1", "trace-1", "order_query", "{\"orderId\":\"A1\"}", "查询成功");
        recorder.recordToolResult("s1", "u1", "trace-1", "order_query", "订单 A1 金额 100");
        recorder.recordSubAgent("s1", "u1", "trace-1", "planner", "制定计划", "计划完成");
        recorder.recordContextInjection("s1", "u1", "trace-1", "历史摘要：用户偏好中文");

        assertEquals(6, repo.logs.size(), "六类事件应全部写入 L0");
        assertEquals(TrajectoryRecorder.SOURCE_SYSTEM_PROMPT, repo.logs.get(0).source());
        assertEquals("system", repo.logs.get(0).role());
        assertEquals(TrajectoryRecorder.SOURCE_CHAIN_OF_THOUGHT, repo.logs.get(1).source());
        assertEquals(TrajectoryRecorder.SOURCE_TOOL_CALL, repo.logs.get(2).source());
        assertEquals("tool", repo.logs.get(2).role());
        assertTrue(repo.logs.get(2).content().contains("order_query"), "工具调用内容应含工具名");
        assertEquals(TrajectoryRecorder.SOURCE_TOOL_RESULT, repo.logs.get(3).source());
        assertEquals(TrajectoryRecorder.SOURCE_SUB_AGENT, repo.logs.get(4).source());
        assertEquals(TrajectoryRecorder.SOURCE_CONTEXT_INJECTION, repo.logs.get(5).source());
        // 同一 traceId 的事件串联，可回放
        repo.logs.forEach(log -> assertEquals("trace-1", log.traceId()));
    }

    @Test
    @DisplayName("空白内容跳过，traceId 缺失自动生成")
    void skipsBlankAndGeneratesTrace() {
        CapturingRepo repo = new CapturingRepo();
        TrajectoryRecorder recorder = new TrajectoryRecorder(repo);

        recorder.record("s1", "u1", "  ", "system", TrajectoryRecorder.SOURCE_SYSTEM_EVENT, "   ", null);
        recorder.record("s1", "u1", null, "system", TrajectoryRecorder.SOURCE_SYSTEM_EVENT, "事件内容", null);

        assertEquals(1, repo.logs.size(), "空白内容应跳过");
        assertNotNull(repo.logs.get(0).traceId(), "缺失 traceId 应自动生成");
        assertTrue(repo.logs.get(0).traceId().startsWith("trace-"));
    }
}
