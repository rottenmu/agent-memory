package com.zimo.module.agentmemory.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zimo.module.agentmemory.memory.AiMemoryService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 记忆 MCP 工具集：将四层记忆服务（AiMemoryService）适配为 WorkBuddy / OpenClaw 生态可调用的
 * MCP 工具（tools/list 元数据 + tools/call 执行）。
 *
 * <p>工具清单（WorkBuddy 深度接管模式）：</p>
 * <ul>
 *   <li>{@code memory_write} — 写入会话/用户/全局记忆（自动敏感脱敏 + 白名单）</li>
 *   <li>{@code memory_read} — 读取会话/用户/全局记忆</li>
 *   <li>{@code memory_delete} — 删除指定记忆（id/key 可空=清空）</li>
 *   <li>{@code memory_search} — 跨目标检索记忆（内容匹配）</li>
 * </ul>
 */
public class MemoryMcpToolkit {

    private final AiMemoryService memoryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MemoryMcpToolkit(AiMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /** MCP tools/list 返回的工具元数据。 */
    public List<Map<String, Object>> listTools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(tool("memory_write",
                "将重要信息写入记忆：会话变量（单次会话）、用户长期记忆（画像/偏好/历史/自定义，自动敏感过滤与白名单校验）、全局记忆。"
                        + "对话中了解到用户偏好、习惯、身份信息或重要事实时应主动写入。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "target", Map.of("type", "string", "enum", List.of("session", "user", "global"),
                                        "description", "记忆目标：session=会话变量，user=用户长期记忆，global=全局记忆"),
                                "sessionId", Map.of("type", "string", "description", "会话标识（target=session 必填）"),
                                "userId", Map.of("type", "string", "description", "用户标识（target=user 必填）"),
                                "category", Map.of("type", "string", "enum", List.of("persona", "preference", "history", "custom"),
                                        "description", "记忆类别（target=user 时使用）"),
                                "key", Map.of("type", "string", "description", "变量名/记忆键（session/global 使用）"),
                                "content", Map.of("type", "string", "description", "记忆内容")),
                        "required", List.of("target", "content"))));
        tools.add(tool("memory_read",
                "读取已保存的记忆：会话变量、用户长期记忆（画像/偏好/历史）、全局记忆。回答涉及用户偏好、历史事实、业务规则前应读取相关记忆。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "target", Map.of("type", "string", "enum", List.of("session", "user", "global"),
                                        "description", "记忆目标"),
                                "sessionId", Map.of("type", "string", "description", "会话标识（target=session 必填）"),
                                "userId", Map.of("type", "string", "description", "用户标识（target=user 必填）"),
                                "category", Map.of("type", "string", "description", "类别过滤（target=user 可选）"),
                                "limit", Map.of("type", "integer", "description", "返回条数（默认 50）")),
                        "required", List.of("target"))));
        tools.add(tool("memory_delete",
                "删除记忆：按 id/key 单条删除；id/key 为空时清空该范围全部记忆（危险操作，需二次确认）。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "target", Map.of("type", "string", "enum", List.of("session", "user", "global"),
                                        "description", "记忆目标"),
                                "sessionId", Map.of("type", "string", "description", "会话标识（target=session 必填）"),
                                "userId", Map.of("type", "string", "description", "用户标识（target=user 必填）"),
                                "key", Map.of("type", "string", "description", "变量名/记忆键（session/global 使用，空=清空）"),
                                "id", Map.of("type", "string", "description", "单条记忆 id（target=user 可选）")),
                        "required", List.of("target"))));
        tools.add(tool("memory_search",
                "跨目标检索记忆（内容包含匹配）：从用户长期记忆与全局记忆中检索与查询词相关的记录，用于快速定位历史事实。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "检索关键词"),
                                "userId", Map.of("type", "string", "description", "限定用户（可选）"),
                                "limit", Map.of("type", "integer", "description", "返回条数（默认 20）")),
                        "required", List.of("query"))));
        return tools;
    }

    /** MCP tools/call 执行入口。 */
    public Map<String, Object> call(String name, Map<String, Object> args) {
        String tenantId = str(args, "tenantId", "default");
        return switch (name) {
            case "memory_write" -> write(tenantId, args);
            case "memory_read" -> read(tenantId, args);
            case "memory_delete" -> delete(tenantId, args);
            case "memory_search" -> search(args);
            default -> Map.of("error", "unknown tool: " + name);
        };
    }

    /* ---------------- 工具执行 ---------------- */

    private Map<String, Object> write(String tenantId, Map<String, Object> args) {
        String target = str(args, "target", "session");
        if (str(args, "content", "").isBlank()) {
            return Map.of("error", "content 不能为空", "target", target);
        }
        return switch (target) {
            case "user" -> memoryService.saveUserMemory(tenantId,
                    str(args, "userId", "unknown"),
                    str(args, "category", "custom"),
                    str(args, "content", ""));
            case "global" -> memoryService.saveGlobalMemory(tenantId,
                    str(args, "key", "mem-" + System.currentTimeMillis()),
                    str(args, "content", ""));
            default -> memoryService.saveSessionVar(tenantId,
                    str(args, "sessionId", "default"),
                    str(args, "key", "var-" + System.currentTimeMillis()),
                    str(args, "content", ""));
        };
    }

    private Map<String, Object> read(String tenantId, Map<String, Object> args) {
        String target = str(args, "target", "session");
        int limit = intArg(args, "limit", 50);
        List<Map<String, Object>> records = switch (target) {
            case "user" -> memoryService.listUserMemory(tenantId,
                    str(args, "userId", "unknown"),
                    nullableStr(args, "category"), limit, 0);
            case "global" -> memoryService.listGlobalMemory(tenantId, limit, 0);
            default -> memoryService.getSessionVars(tenantId,
                    str(args, "sessionId", "default"), limit, 0);
        };
        return Map.of("target", target, "count", records.size(), "records", records);
    }

    private Map<String, Object> delete(String tenantId, Map<String, Object> args) {
        String target = str(args, "target", "session");
        String key = nullableStr(args, "key");
        String id = nullableStr(args, "id");
        switch (target) {
            case "user" -> memoryService.deleteUserMemory(tenantId, str(args, "userId", "unknown"), id);
            case "global" -> memoryService.deleteGlobalMemory(tenantId, key);
            default -> memoryService.deleteSessionVar(tenantId, str(args, "sessionId", "default"),
                    key == null ? "" : key);
        }
        return Map.of("deleted", true, "target", target);
    }

    private Map<String, Object> search(Map<String, Object> args) {
        String query = str(args, "query", "");
        int limit = intArg(args, "limit", 20);
        if (query.isBlank()) {
            return Map.of("error", "query 不能为空");
        }
        String q = query.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> hits = new ArrayList<>();
        String userId = nullableStr(args, "userId");
        // 用户长期记忆（L3 画像 + L1）
        if (userId != null && !userId.isBlank()) {
            for (Map<String, Object> record : memoryService.listUserMemory("default", userId, null, 500, 0)) {
                if (matches(record, q)) {
                    record.put("hitTarget", "user");
                    hits.add(record);
                    if (hits.size() >= limit) {
                        break;
                    }
                }
            }
        }
        // 全局记忆
        if (hits.size() < limit) {
            for (Map<String, Object> record : memoryService.listGlobalMemory("default", 500, 0)) {
                if (matches(record, q)) {
                    record.put("hitTarget", "global");
                    hits.add(record);
                    if (hits.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return Map.of("query", query, "count", hits.size(), "records", hits);
    }

    /* ---------------- 工具 ---------------- */

    private boolean matches(Map<String, Object> record, String lowerQuery) {
        Object content = record.get("content");
        Object id = record.get("id");
        if (content != null && String.valueOf(content).toLowerCase(Locale.ROOT).contains(lowerQuery)) {
            return true;
        }
        return id != null && String.valueOf(id).toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> inputSchema) {
        Map<String, Object> toolMap = new LinkedHashMap<>();
        toolMap.put("name", name);
        toolMap.put("description", description);
        toolMap.put("inputSchema", inputSchema);
        return toolMap;
    }

    private static String str(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static String nullableStr(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static int intArg(Map<String, Object> args, String key, int fallback) {
        Object value = args.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // fallback
            }
        }
        return fallback;
    }
}
