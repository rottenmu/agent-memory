package com.zimo.module.agentmemory.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * 将记忆服务适配为 AgentScope 可调用工具，使智能体能在对话中主动读写记忆。
 *
 * <ul>
 *   <li>{@code memory_write}：写入会话变量 / 用户长期记忆 / 全局记忆（自动敏感过滤与白名单校验）</li>
 *   <li>{@code memory_read}：读取会话变量 / 用户长期记忆 / 全局记忆</li>
 * </ul>
 *
 * @author WorkBuddy
 * @since 2026-08-08
 */
public final class AiMemoryAgentTool {

    private static final Map<String, Object> WRITE_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "target", Map.of("type", "string", "enum", List.of("session", "user", "global"),
                            "description", "记忆目标：session=会话变量，user=用户长期记忆，global=全局记忆"),
                    "sessionId", Map.of("type", "string", "description", "会话标识（target=session 时必填）"),
                    "userId", Map.of("type", "string", "description", "用户标识（target=user 时必填）"),
                    "category", Map.of("type", "string",
                            "description", "记忆类别：persona=员工画像，preference=历史习惯，history=业务历史记录，custom=自定义（target=user 时建议填写）"),
                    "key", Map.of("type", "string", "description", "变量名 / 记忆键"),
                    "content", Map.of("type", "string", "description", "记忆内容")),
            "required", List.of("target", "content"));

    private static final Map<String, Object> READ_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "target", Map.of("type", "string", "enum", List.of("session", "user", "global"),
                            "description", "记忆目标：session=会话变量，user=用户长期记忆，global=全局记忆"),
                    "sessionId", Map.of("type", "string", "description", "会话标识（target=session 时必填）"),
                    "userId", Map.of("type", "string", "description", "用户标识（target=user 时必填）"),
                    "category", Map.of("type", "string", "description", "类别过滤（target=user 时可填）")),
            "required", List.of("target"));

    private AiMemoryAgentTool() {
    }

    /** 注册全部记忆工具到目标 Toolkit。 */
    public static void register(io.agentscope.core.tool.Toolkit toolkit, AiMemoryService service, String tenantId) {
        toolkit.registerAgentTool(new MemoryWriteTool(service, tenantId));
        toolkit.registerAgentTool(new MemoryReadTool(service, tenantId));
    }

    /* ---------------- 写入工具 ---------------- */

    static final class MemoryWriteTool extends ToolBase {
        private final AiMemoryService service;
        private final String tenantId;

        MemoryWriteTool(AiMemoryService service, String tenantId) {
            super(ToolBase.builder()
                    .name("memory_write")
                    .description("将重要信息写入记忆：会话变量（单次会话）、用户长期记忆（画像/习惯/业务历史，自动敏感过滤）、全局记忆。"
                            + "对话中了解到用户的偏好、习惯、身份信息或重要事实时应主动写入记忆。")
                    .inputSchema(WRITE_SCHEMA)
                    .readOnly(false)
                    .concurrencySafe(false));
            this.service = Objects.requireNonNull(service, "service must not be null");
            this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            Map<String, Object> args = param == null ? Map.of() : param.getInput();
            String target = str(args.get("target"), "session");
            try {
                Map<String, Object> result = switch (target) {
                    case "user" -> service.saveUserMemory(tenantId,
                            str(args.get("userId"), "unknown"),
                            str(args.get("category"), "custom"),
                            str(args.get("content"), ""));
                    case "global" -> service.saveGlobalMemory(tenantId,
                            str(args.get("key"), "mem-" + System.currentTimeMillis()),
                            str(args.get("content"), ""));
                    default -> service.saveSessionVar(tenantId,
                            str(args.get("sessionId"), "default"),
                            str(args.get("key"), "var-" + System.currentTimeMillis()),
                            str(args.get("content"), ""));
                };
                return Mono.just(ToolResultBlock.text(toJson(result)));
            } catch (Exception e) {
                return Mono.just(ToolResultBlock.error("记忆写入失败：" + safeMessage(e)));
            }
        }
    }

    /* ---------------- 读取工具 ---------------- */

    static final class MemoryReadTool extends ToolBase {
        private final AiMemoryService service;
        private final String tenantId;

        MemoryReadTool(AiMemoryService service, String tenantId) {
            super(ToolBase.builder()
                    .name("memory_read")
                    .description("读取已保存的记忆：会话变量、用户长期记忆（画像/习惯/业务历史）、全局记忆。"
                            + "回答涉及用户偏好、历史事实、业务规则前应读取相关记忆。")
                    .inputSchema(READ_SCHEMA)
                    .readOnly(true)
                    .concurrencySafe(true));
            this.service = Objects.requireNonNull(service, "service must not be null");
            this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            Map<String, Object> args = param == null ? Map.of() : param.getInput();
            String target = str(args.get("target"), "session");
            try {
                List<Map<String, Object>> records = switch (target) {
                    case "user" -> service.listUserMemory(tenantId,
                            str(args.get("userId"), "unknown"),
                            str(args.get("category"), null));
                    case "global" -> service.listGlobalMemory(tenantId);
                    default -> service.getSessionVars(tenantId,
                            str(args.get("sessionId"), "default"));
                };
                if (records.isEmpty()) {
                    return Mono.just(ToolResultBlock.text("[]（暂无该范围记忆）"));
                }
                return Mono.just(ToolResultBlock.text(toJson(records)));
            } catch (Exception e) {
                return Mono.just(ToolResultBlock.error("记忆读取失败：" + safeMessage(e)));
            }
        }
    }

    /* ---------------- 工具 ---------------- */

    private static String str(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static String toJson(Object value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String safeMessage(Throwable exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }
}
