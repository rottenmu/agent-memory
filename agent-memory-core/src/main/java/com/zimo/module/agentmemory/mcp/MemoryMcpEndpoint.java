package com.zimo.module.agentmemory.mcp;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WorkBuddy / OpenClaw 生态 MCP 接入端点（HTTP JSON-RPC 2.0）。
 *
 * <p>方法：</p>
 * <ul>
 *   <li>{@code tools/list} — 返回记忆工具元数据（memory_write/read/delete/search）</li>
 *   <li>{@code tools/call} — 执行记忆工具（基于 H2 四层记忆，深度接管）</li>
 * </ul>
 *
 * <p>WorkBuddy 客户端在 {@code mcp.json} 中以 HTTP transport 注册本端点
 * （见 resources/mcp/memory-mcp.json 模板），即可在对话中直接调用记忆工具。</p>
 *
 * <p><b>准入</b>：本端点被 {@code plugin.auth} 拦截器排除在白名单外，鉴权由
 * {@link MemoryMcpAccessGuard} 在 POST 入口完成 —— 配置了 {@code agent-memory.mcp-token}
 * 时校验 {@code Authorization}（Bearer 或裸 token），未配置时仅放行回环来源。
 * 未通过返回 HTTP 401 + JSON-RPC 错误体。</p>
 */
@RestController
@RequestMapping("/api/agent-memory/mcp")
public class MemoryMcpEndpoint {

    private final MemoryMcpToolkit toolkit;
    private final MemoryMcpAccessGuard accessGuard;

    public MemoryMcpEndpoint(MemoryMcpToolkit toolkit, MemoryMcpAccessGuard accessGuard) {
        this.toolkit = toolkit;
        this.accessGuard = accessGuard;
    }

    /**
     * SSE 探测/GET 访问：以 text/event-stream 返回空事件（避免 405/406；实际调用走 POST）。
     *
     * <p>刻意不做准入校验：响应体是固定常量、零信息量，而部分 MCP 客户端在建立 SSE 通道时
     * 不携带自定义头，拦下来只会让连接失败却不提升任何安全性。所有读写入口都在 POST 上。</p>
     */
    @org.springframework.web.bind.annotation.GetMapping(produces = "text/event-stream")
    public org.springframework.http.ResponseEntity<String> handleGet() {
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.TEXT_EVENT_STREAM)
                .body("data: {\"jsonrpc\":\"2.0\",\"id\":null,\"result\":{}}\n\n");
    }

    /**
     * 工具调用入口。校验不通过返回 HTTP 401 + JSON-RPC 错误体：
     * HTTP 状态码让客户端（含本平台的 MCP 授权弹窗）能识别「需要凭据」，错误文案说明缺什么。
     */
    @PostMapping
    public org.springframework.http.ResponseEntity<McpResponse> handle(
            @RequestBody McpRequest request, HttpServletRequest servletRequest) {
        if (!accessGuard.isAllowed(servletRequest.getRemoteAddr(),
                servletRequest.getHeader(org.springframework.http.HttpHeaders.AUTHORIZATION))) {
            return org.springframework.http.ResponseEntity
                    .status(org.springframework.http.HttpStatus.UNAUTHORIZED)
                    .body(McpResponse.error(request.id(), -32001, accessGuard.denyReason()));
        }
        return org.springframework.http.ResponseEntity.ok(dispatch(request));
    }

    /** 按 JSON-RPC 方法分发（校验已在上层完成）。 */
    private McpResponse dispatch(McpRequest request) {
        // MCP 协议握手（WorkBuddy 标准 MCP 客户端连接时先调用）
        if ("initialize".equals(request.method())) {
            // 规范：initialize result 为 {protocolVersion, capabilities, serverInfo} 直接对象
            return new McpResponse("2.0", request.id(), Map.of(
                    "protocolVersion", "2024-11-05",
                    "capabilities", Map.of("tools", Map.of()),
                    "serverInfo", Map.of("name", "agent-memory", "version", "1.0.0")), null);
        }
        // 客户端通知（无需响应内容）
        if ("notifications/initialized".equals(request.method())
                || "notifications/tools/list_changed".equals(request.method())) {
            return new McpResponse("2.0", request.id(), Map.of(), null);
        }
        if ("tools/list".equals(request.method())) {
            // 规范：tools/list result 为 {tools: [...]} 对象
            return new McpResponse("2.0", request.id(), Map.of("tools", toolkit.listTools()), null);
        }
        if ("tools/call".equals(request.method())) {
            // MCP 规范：params = {name, arguments}
            String name = String.valueOf(request.params().get("name"));
            Map<String, Object> arguments = extractArguments(request.params());
            try {
                Map<String, Object> result = toolkit.call(name, arguments);
                return McpResponse.ok(request.id(), toJson(result));
            } catch (Exception e) {
                return McpResponse.error(request.id(), -32000,
                        "memory tool '" + name + "' failed: " + safeMessage(e));
            }
        }
        return McpResponse.error(request.id(), -32601, "Unknown MCP method: " + request.method());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractArguments(Map<String, Object> params) {
        // 标准：params.arguments
        Object args = params.get("arguments");
        if (args instanceof Map<?, ?> argMap) {
            return (Map<String, Object>) argMap;
        }
        // 兼容历史嵌套格式：params.params.arguments（早期 curl 测试）
        Object nested = params.get("params");
        if (nested instanceof Map<?, ?> nestedMap) {
            Object nestedArgs = nestedMap.get("arguments");
            if (nestedArgs instanceof Map<?, ?> argMap) {
                return (Map<String, Object>) argMap;
            }
        }
        return Map.of();
    }

    private String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String safeMessage(Throwable exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }
}
