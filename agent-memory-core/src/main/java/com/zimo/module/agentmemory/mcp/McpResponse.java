package com.zimo.module.agentmemory.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * 轻量 MCP JSON-RPC 响应信封。
 *
 * <p>MCP 协议要求 result 与 error 互斥：成功只含 result，失败只含 error；
 * 通过 {@code NON_NULL} 排除 null 字段，满足客户端严格 schema 校验。</p>
 *
 * @param jsonrpc 协议版本（2.0）
 * @param id      请求 ID
 * @param result  成功结果（MCP 规范：content 数组；失败时为 null 且不序列化）
 * @param error   错误信息（JSON-RPC error 对象；成功时为 null 且不序列化）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpResponse(String jsonrpc, Object id, Object result, Object error) {

    /** 成功响应：text 内容封装为 MCP content 数组。 */
    public static McpResponse ok(Object id, Object textContent) {
        Object content = List.of(Map.of("type", "text", "text", String.valueOf(textContent)));
        return new McpResponse("2.0", id, Map.of("content", content), null);
    }

    /** 错误响应。 */
    public static McpResponse error(Object id, int code, String message) {
        return new McpResponse("2.0", id, null, Map.of("code", code, "message", message));
    }
}
