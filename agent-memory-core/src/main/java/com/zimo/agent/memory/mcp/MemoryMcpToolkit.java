package com.zimo.agent.memory.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zimo.agent.memory.memory.AiMemoryService;
import com.zimo.agent.memory.model.MemoryPage;
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
 *   <li>{@code memory_read} — 读取会话/用户/全局记忆（返回 {@code total} / {@code truncated}）</li>
 *   <li>{@code memory_delete} — 删除指定记忆（id/key 可空=清空，返回真实删除条数）</li>
 *   <li>{@code memory_search} — 跨目标检索记忆（内容匹配）</li>
 * </ul>
 *
 * <p>{@code inputSchema} 已声明 {@code tenantId}：{@code call()} 一直会读它，但 schema 未声明时
 * 规范客户端永远不会传，实际恒为 {@code default}，租户隔离在 MCP 入口等于失效。</p>
 */
public class MemoryMcpToolkit {

    /** 租户参数说明（四个工具共用，避免文案漂移）。 */
    private static final String TENANT_DESC =
            "租户标识（多租户隔离维度，默认 default；不同租户的同名 userId 互不可见）";

    /**
     * 重要性参数说明。
     *
     * <p>必须把「≥ 0.9 = 免于遗忘淘汰」写进描述：这是模型唯一能表达「这条绝不允许删」的途径，
     * 不写清楚，模型永远不会传，保护机制等于不存在。</p>
     */
    private static final String IMPORTANCE_DESC =
            "重要性 0~1。省略时由服务端按内容与类别自动评估；"
                    + "传入 0.9 及以上视为「显式标记为重要」，该条记忆免于遗忘淘汰";

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
                                "tenantId", Map.of("type", "string", "description", TENANT_DESC),
                                "importance", Map.of("type", "number", "description", IMPORTANCE_DESC),
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
                                "tenantId", Map.of("type", "string", "description", TENANT_DESC),
                                "limit", Map.of("type", "integer", "description", "返回条数（默认 50）")),
                        "required", List.of("target"))));
        tools.add(tool("memory_delete",
                "删除记忆：按 id/key 单条删除；id/key 为空时清空该范围全部记忆（危险操作，需二次确认）。返回真实删除条数。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "target", Map.of("type", "string", "enum", List.of("session", "user", "global"),
                                        "description", "记忆目标"),
                                "sessionId", Map.of("type", "string", "description", "会话标识（target=session 必填）"),
                                "userId", Map.of("type", "string", "description", "用户标识（target=user 必填）"),
                                "key", Map.of("type", "string", "description", "变量名/记忆键（session/global 使用，空=清空）"),
                                "tenantId", Map.of("type", "string", "description", TENANT_DESC),
                                "id", Map.of("type", "string", "description", "单条记忆 id（target=user 可选）")),
                        "required", List.of("target"))));
        tools.add(tool("memory_search",
                "跨目标检索记忆（内容包含匹配）：从用户长期记忆与全局记忆中检索与查询词相关的记录，用于快速定位历史事实。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "检索关键词"),
                                "userId", Map.of("type", "string", "description", "限定用户（可选）"),
                                "tenantId", Map.of("type", "string", "description", TENANT_DESC),
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
            case "memory_search" -> search(tenantId, args);
            default -> Map.of("error", "unknown tool: " + name);
        };
    }

    /* ---------------- 工具执行 ---------------- */

    /**
     * 写入。{@code importance} 非法时返回错误而不是忽略：MCP 侧同样不接受无声失败。
     */
    private Map<String, Object> write(String tenantId, Map<String, Object> args) {
        String target = str(args, "target", "session");
        if (str(args, "content", "").isBlank()) {
            return Map.of("error", "content 不能为空", "target", target);
        }
        Double importance;
        try {
            importance = numberOrNull(args, "importance");
        } catch (IllegalArgumentException e) {
            return Map.of("error", e.getMessage(), "target", target);
        }
        return switch (target) {
            case "user" -> memoryService.saveUserMemory(tenantId,
                    str(args, "userId", "unknown"),
                    str(args, "category", "custom"),
                    str(args, "content", ""),
                    importance);
            case "global" -> memoryService.saveGlobalMemory(tenantId,
                    str(args, "key", "mem-" + System.currentTimeMillis()),
                    str(args, "content", ""),
                    importance);
            default -> memoryService.saveSessionVar(tenantId,
                    str(args, "sessionId", "default"),
                    str(args, "key", "var-" + System.currentTimeMillis()),
                    str(args, "content", ""));
        };
    }

    /** 读取：返回 {@code total} 与 {@code truncated}，让调用方知道结果是否被截断。 */
    private Map<String, Object> read(String tenantId, Map<String, Object> args) {
        String target = str(args, "target", "session");
        int limit = intArg(args, "limit", 50);
        MemoryPage page = switch (target) {
            case "user" -> memoryService.listUserMemory(tenantId,
                    str(args, "userId", "unknown"),
                    nullableStr(args, "category"), limit, 0);
            case "global" -> memoryService.listGlobalMemory(tenantId, limit, 0);
            default -> memoryService.getSessionVars(tenantId,
                    str(args, "sessionId", "default"), limit, 0);
        };
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", target);
        result.put("count", page.records().size());
        result.put("total", page.total());
        result.put("truncated", page.truncated());
        result.put("records", page.records());
        return result;
    }

    /** 删除：{@code deleted} 由真实影响行数推导。 */
    private Map<String, Object> delete(String tenantId, Map<String, Object> args) {
        String target = str(args, "target", "session");
        String key = nullableStr(args, "key");
        String id = nullableStr(args, "id");
        int deleted = switch (target) {
            case "user" -> memoryService.deleteUserMemory(tenantId, str(args, "userId", "unknown"), id);
            case "global" -> memoryService.deleteGlobalMemory(tenantId, key);
            default -> memoryService.deleteSessionVar(tenantId,
                    str(args, "sessionId", "default"), key);
        };
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", target);
        result.put("deleted", deleted > 0);
        result.put("deletedCount", deleted);
        return result;
    }

    /**
     * 跨目标检索。租户必须透传 —— 曾经这里把租户写死为 {@code default}，与同一进程内
     * {@code memory_read}/{@code memory_write} 按真实租户读写的行为不一致：只要调用方带上了
     * tenantId，检索结果就会跨租户串到 default 的数据上。
     */
    private Map<String, Object> search(String tenantId, Map<String, Object> args) {
        String query = str(args, "query", "");
        int limit = intArg(args, "limit", 20);
        if (query.isBlank()) {
            return Map.of("error", "query 不能为空");
        }
        String q = query.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> hits = new ArrayList<>();
        String userId = nullableStr(args, "userId");
        // 用户长期记忆（L3 画像 ∪ L1）
        if (userId != null && !userId.isBlank()) {
            List<Map<String, Object>> records =
                    memoryService.listUserMemory(tenantId, userId, null, SEARCH_SCAN, 0).records();
            collect(records, q, "user", limit, hits);
        }
        // 全局记忆
        if (hits.size() < limit) {
            List<Map<String, Object>> records =
                    memoryService.listGlobalMemory(tenantId, SEARCH_SCAN, 0).records();
            collect(records, q, "global", limit, hits);
        }
        return Map.of("query", query, "count", hits.size(), "records", hits);
    }

    /* ---------------- 工具 ---------------- */

    /** 检索时的取数上限（内存过滤，非分页路径）。 */
    private static final int SEARCH_SCAN = 1000;

    private void collect(List<Map<String, Object>> records, String lowerQuery, String hitTarget,
            int limit, List<Map<String, Object>> hits) {
        for (Map<String, Object> record : records) {
            if (hits.size() >= limit) {
                return;
            }
            if (matches(record, lowerQuery)) {
                record.put("hitTarget", hitTarget);
                hits.add(record);
            }
        }
    }

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

    /**
     * 取可选数值参数。
     *
     * @param args 工具入参
     * @param key  参数名
     * @return 数值；缺失或空白时返回 {@code null}
     * @throws IllegalArgumentException 参数存在但不是合法数值
     */
    private static Double numberOrNull(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 必须是 0~1 的数值，当前为: " + value);
        }
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
