package com.zimo.module.agentmemory.mcp;

import java.util.Map;
import java.util.List;

/**
 * 轻量 MCP JSON-RPC 信封（与平台 /api/ai/mcp 同构，独立于 starter 以避免循环依赖）。
 *
 * @param jsonrpc 协议版本（2.0）
 * @param id      请求 ID（响应原样返回）
 * @param method  MCP 方法：tools/list / tools/call
 * @param params  方法参数
 */
public record McpRequest(String jsonrpc, Object id, String method, Map<String, Object> params) {

    public McpRequest {
        jsonrpc = jsonrpc == null ? "2.0" : jsonrpc;
        params = params == null ? Map.of() : params;
    }
}
