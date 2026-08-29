package com.zimo.module.agentmemory.memory;

import com.zimo.module.agentmemory.model.L0RawLog;
import com.zimo.module.agentmemory.storage.OltpMemoryRepository;
import cn.hutool.core.util.IdUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话轨迹（Trajectory）事件采集器：把"模型看到的一切"写入仅追加的 L0 事件流。
 *
 * <p>模型可见输入输出全量落盘，按来源（source）分类，供 Trajectory 视图按来源查看；
 * 恢复、分叉、检索与回放统一基于同一份 L0 事件流。采集失败不影响主流程
 * （捕获异常仅记日志，降级跳过）。</p>
 *
 * <p>来源枚举（与 L0RawLog.source 对应）：</p>
 * <ul>
 *   <li>{@link #SOURCE_SYSTEM_PROMPT} 系统提示词（会话初始化注入）</li>
 *   <li>{@link #SOURCE_CHAIN_OF_THOUGHT} 思维链</li>
 *   <li>{@link #SOURCE_TOOL_CALL} 工具调用（含参数）</li>
 *   <li>{@link #SOURCE_TOOL_RESULT} 工具执行结果</li>
 *   <li>{@link #SOURCE_SUB_AGENT} 子 Agent 调度（含任务/结果摘要）</li>
 *   <li>{@link #SOURCE_CONTEXT_INJECTION} 上下文注入（历史摘要、检索片段、记忆注入）</li>
 *   <li>{@link #SOURCE_USER_MESSAGE} / {@link #SOURCE_ASSISTANT_MESSAGE} 对话消息</li>
 *   <li>{@link #SOURCE_SYSTEM_EVENT} 系统事件</li>
 * </ul>
 */
public class TrajectoryRecorder {

    private static final Logger log = LoggerFactory.getLogger(TrajectoryRecorder.class);

    /* ---------- Trajectory 来源枚举 ---------- */
    public static final String SOURCE_USER_MESSAGE = "user_message";
    public static final String SOURCE_ASSISTANT_MESSAGE = "assistant_message";
    public static final String SOURCE_SYSTEM_PROMPT = "system_prompt";
    public static final String SOURCE_CHAIN_OF_THOUGHT = "chain_of_thought";
    public static final String SOURCE_TOOL_CALL = "tool_call";
    public static final String SOURCE_TOOL_RESULT = "tool_result";
    public static final String SOURCE_SUB_AGENT = "sub_agent";
    public static final String SOURCE_CONTEXT_INJECTION = "context_injection";
    public static final String SOURCE_SYSTEM_EVENT = "system_event";

    private final OltpMemoryRepository oltp;

    public TrajectoryRecorder(OltpMemoryRepository oltp) {
        this.oltp = oltp;
    }

    /* ---------- 六类模型可见事件 ---------- */

    /** 系统提示词（会话初始化注入）。 */
    public void recordSystemPrompt(String sessionId, String userId, String traceId, String prompt) {
        record(sessionId, userId, traceId, "system", SOURCE_SYSTEM_PROMPT, prompt, null);
    }

    /** 思维链（推理循环中的中间思考）。 */
    public void recordChainOfThought(String sessionId, String userId, String traceId, String content, Integer tokens) {
        String meta = tokens == null ? null : "{\"tokens\":" + tokens + "}";
        record(sessionId, userId, traceId, "assistant", SOURCE_CHAIN_OF_THOUGHT, content, meta);
    }

    /** 工具调用（含工具名、参数与调用结果摘要）。 */
    public void recordToolCall(String sessionId, String userId, String traceId,
                               String toolName, String args, String resultSummary) {
        String content = "调用工具【" + toolName + "】"
                + (args == null || args.isBlank() ? "" : "，参数：" + args)
                + (resultSummary == null || resultSummary.isBlank() ? "" : "，结果：" + resultSummary);
        record(sessionId, userId, traceId, "tool", SOURCE_TOOL_CALL, content, null);
    }

    /** 工具执行结果（完整结果单独记录，便于回放与检索）。 */
    public void recordToolResult(String sessionId, String userId, String traceId,
                                 String toolName, String result) {
        String content = "工具【" + toolName + "】执行结果：" + result;
        record(sessionId, userId, traceId, "tool", SOURCE_TOOL_RESULT, content, null);
    }

    /** 子 Agent 调度（任务描述 + 结果摘要）。 */
    public void recordSubAgent(String sessionId, String userId, String traceId,
                               String agentName, String task, String resultSummary) {
        String content = "子 Agent【" + agentName + "】调度"
                + (task == null || task.isBlank() ? "" : "，任务：" + task)
                + (resultSummary == null || resultSummary.isBlank() ? "" : "，结果：" + resultSummary);
        record(sessionId, userId, traceId, "assistant", SOURCE_SUB_AGENT, content, null);
    }

    /** 上下文注入（历史摘要、检索片段、记忆注入等）。 */
    public void recordContextInjection(String sessionId, String userId, String traceId, String segment) {
        record(sessionId, userId, traceId, "system", SOURCE_CONTEXT_INJECTION, segment, null);
    }

    /* ---------- 通用 ---------- */

    /**
     * 通用落盘入口：写入一条 L0 Trajectory 事件；任何异常仅记日志，不影响主流程。
     *
     * @param metaJson 附加元数据（模型名、渠道、耗时等，可空）
     */
    public void record(String sessionId, String userId, String traceId,
                       String role, String source, String content, String metaJson) {
        try {
            if (content == null || content.isBlank()) {
                return;
            }
            String trace = (traceId == null || traceId.isBlank())
                    ? "trace-" + IdUtil.fastSimpleUUID().substring(0, 12) : traceId;
            oltp.saveRawLog(L0RawLog.forInsert(
                    trace, sessionId, userId, System.currentTimeMillis(), role, content, null, metaJson, source));
        } catch (Exception e) {
            log.warn("Trajectory 事件写入失败 sessionId={} source={}: {}", sessionId, source, e.getMessage());
        }
    }
}
