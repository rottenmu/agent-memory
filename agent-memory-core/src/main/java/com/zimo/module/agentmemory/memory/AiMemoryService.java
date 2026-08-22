package com.zimo.module.agentmemory.memory;

import cn.hutool.core.util.IdUtil;
import com.zimo.module.agentmemory.model.L0RawLog;
import com.zimo.module.agentmemory.model.L1AtomicMemory;
import com.zimo.module.agentmemory.model.L3Persona;
import com.zimo.module.agentmemory.security.AiMemorySensitiveFilter;
import com.zimo.module.agentmemory.storage.OltpMemoryRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体记忆服务（迁移自 ai-agent-spring-boot-starter，落库改为四层金字塔）。
 *
 * <p>原「会话变量/用户长期记忆/全局记忆」映射到四层模型：</p>
 * <ul>
 *   <li>会话变量 → L1 原子记忆（{@code session_var} 类型），按会话召回</li>
 *   <li>用户长期记忆 → L3 画像（persona/preference 类别）与 L1（history/custom）</li>
 *   <li>全局记忆 → L1（{@code global} 类型）</li>
 * </ul>
 * <p>同时写入 L0 原始日志（traceId 溯源）。安全策略：类别白名单 + 敏感脱敏。</p>
 */
public class AiMemoryService {

    /** L1 记忆类型：会话变量。 */
    public static final String TYPE_SESSION_VAR = "session_var";
    /** L1 记忆类型：全局记忆。 */
    public static final String TYPE_GLOBAL = "global";

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final OltpMemoryRepository oltp;
    private final AiMemorySensitiveFilter sensitiveFilter;
    private final List<String> whitelistCategories;
    private final boolean sensitiveFiltering;

    /**
     * @param oltp             四层 OLTP 记忆仓储
     * @param sensitiveFilter  敏感内容过滤器
     * @param securityConfig   记忆安全策略（白名单类别 + 敏感过滤开关）
     */
    public AiMemoryService(
            OltpMemoryRepository oltp,
            AiMemorySensitiveFilter sensitiveFilter,
            MemorySecurityConfig securityConfig) {
        this.oltp = oltp;
        this.sensitiveFilter = sensitiveFilter;
        this.whitelistCategories = securityConfig == null
                ? List.of() : securityConfig.whitelistCategories();
        this.sensitiveFiltering = securityConfig == null
                || securityConfig.sensitiveFiltering();
    }

    /* ---------------- 短期会话变量（L1 session_var） ---------------- */

    /** 保存单次会话的业务变量（L1 原子记忆 + L0 原始日志）。 */
    public Map<String, Object> saveSessionVar(String tenantId, String sessionId, String key, String value) {
        requireText(tenantId, "租户标识不能为空");
        requireText(sessionId, "会话标识不能为空");
        requireText(key, "变量名不能为空");
        String safeValue = sanitizeIfEnabled(value);
        String id = IdUtil.fastSimpleUUID().substring(0, 12);
        String traceId = "trace-" + id;
        long ts = System.currentTimeMillis();
        oltp.saveRawLog(L0RawLog.forInsert(traceId, sessionId, tenantId, ts, "system",
                "SESSION_VAR:" + key + "=" + safeValue, null, null));
        oltp.saveAtomicMemory(new L1AtomicMemory(
                id, traceId, sessionId, tenantId, TYPE_SESSION_VAR,
                key + "=" + safeValue, null, ts));
        Map<String, Object> record = record(key, safeValue, Map.of("sessionId", sessionId));
        return result(record, value, safeValue);
    }

    /** 读取会话变量列表（按时间升序）。 */
    public List<Map<String, Object>> getSessionVars(String tenantId, String sessionId) {
        return getSessionVars(tenantId, sessionId, null, null);
    }

    /** 读取会话变量列表（支持分页）。 */
    public List<Map<String, Object>> getSessionVars(String tenantId, String sessionId, Integer limit, Integer offset) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (L1AtomicMemory memory : oltp.recallAtomicBySession(sessionId, 500)) {
            if (TYPE_SESSION_VAR.equals(memory.memoryType())) {
                String[] parts = memory.content().split("=", 2);
                records.add(record(parts.length > 0 ? parts[0] : memory.id(),
                        parts.length > 1 ? parts[1] : "", Map.of("sessionId", sessionId)));
            }
        }
        records.sort(Comparator.comparingLong(this::timestampOf));
        return paginate(records, limit, offset);
    }

    /** 删除会话变量。 */
    public void deleteSessionVar(String tenantId, String sessionId, String key) {
        if (hasText(tenantId) && hasText(sessionId) && hasText(key)) {
            for (L1AtomicMemory memory : oltp.recallAtomicBySession(sessionId, 500)) {
                if (TYPE_SESSION_VAR.equals(memory.memoryType())
                        && memory.content().startsWith(key + "=")) {
                    oltp.deleteAtomicMemory(memory.id());
                }
            }
        }
    }

    /* ---------------- 用户长期记忆（L3 画像 + L1） ---------------- */

    /** 保存用户长期记忆（persona/preference → L3 画像；history/custom → L1）。 */
    public Map<String, Object> saveUserMemory(String tenantId, String userId, String category, String content) {
        requireText(tenantId, "租户标识不能为空");
        requireText(userId, "用户标识不能为空");
        String normalizedCategory = hasText(category) ? category.trim() : "custom";
        if (!isCategoryAllowed(normalizedCategory)) {
            return Map.of("allowed", false, "reason",
                    "记忆类别不在白名单内，已拒绝写入: " + normalizedCategory);
        }
        String safeValue = sanitizeIfEnabled(content);
        if (!hasText(safeValue)) {
            return Map.of("allowed", false, "reason", "记忆内容为空或全部为敏感信息，已拒绝写入");
        }
        String id = IdUtil.fastSimpleUUID().substring(0, 12);
        String traceId = "trace-" + id;
        long ts = System.currentTimeMillis();
        oltp.saveRawLog(L0RawLog.forInsert(traceId, "user-" + userId, tenantId, ts, "system",
                "USER_MEMORY:" + normalizedCategory + "=" + safeValue, null, null));
        if ("persona".equals(normalizedCategory) || "preference".equals(normalizedCategory)) {
            oltp.savePersona(new L3Persona(id, userId, normalizedCategory, safeValue, 1, ts));
        } else {
            oltp.saveAtomicMemory(new L1AtomicMemory(
                    id, traceId, "user-" + userId, userId, normalizedCategory, safeValue, null, ts));
        }
        Map<String, Object> record = record(id, safeValue, Map.of(
                "userId", userId, "category", normalizedCategory));
        return result(record, content, safeValue);
    }

    /** 查询用户长期记忆（L3 画像 + L1 合并，按时间降序，支持分页）。 */
    public List<Map<String, Object>> listUserMemory(String tenantId, String userId, String category) {
        return listUserMemory(tenantId, userId, category, null, null);
    }

    /** 查询用户长期记忆（支持分页）。 */
    public List<Map<String, Object>> listUserMemory(String tenantId, String userId, String category, Integer limit, Integer offset) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (L3Persona persona : oltp.listPersonas(userId)) {
            if (!hasText(category) || category.equals(persona.personaType())) {
                records.add(record(persona.id(), persona.content(),
                        Map.of("userId", userId, "category", persona.personaType(),
                                "version", persona.version())));
            }
        }
        for (L1AtomicMemory memory : oltp.recallAtomicByType(userId, hasText(category) ? category : "", 500)) {
            records.add(record(memory.id(), memory.content(),
                    Map.of("userId", userId, "category", memory.memoryType())));
        }
        records.sort((a, b) -> Long.compare(timestampOf(b), timestampOf(a)));
        return paginate(records, limit, offset);
    }

    /** 删除指定用户记忆；id 为空时删除该用户全部画像与原子记忆。 */
    public void deleteUserMemory(String tenantId, String userId, String id) {
        if (hasText(id)) {
            oltp.deleteAtomicMemory(id);
        } else {
            for (L3Persona persona : oltp.listPersonas(userId)) {
                oltp.deletePersona(userId, persona.personaType());
            }
            for (L1AtomicMemory memory : oltp.recallAtomicByType(userId, "", 10_000)) {
                oltp.deleteAtomicMemory(memory.id());
            }
        }
    }

    /* ---------------- 全局持久记忆（L1 global） ---------------- */

    /** 保存全局持久记忆。 */
    public Map<String, Object> saveGlobalMemory(String tenantId, String key, String content) {
        requireText(tenantId, "租户标识不能为空");
        requireText(key, "记忆键不能为空");
        String safeValue = sanitizeIfEnabled(content);
        String id = IdUtil.fastSimpleUUID().substring(0, 12);
        long ts = System.currentTimeMillis();
        oltp.saveAtomicMemory(new L1AtomicMemory(
                id, "trace-" + id, "global", tenantId, TYPE_GLOBAL,
                key + "=" + safeValue, null, ts));
        Map<String, Object> record = record(key, safeValue, Map.of("global", true));
        return result(record, content, safeValue);
    }

    /** 查询全局持久记忆列表（按时间降序）。 */
    public List<Map<String, Object>> listGlobalMemory(String tenantId) {
        return listGlobalMemory(tenantId, null, null);
    }

    /** 查询全局持久记忆列表（支持分页）。 */
    public List<Map<String, Object>> listGlobalMemory(String tenantId, Integer limit, Integer offset) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (L1AtomicMemory memory : oltp.recallAtomicByType(tenantId, TYPE_GLOBAL, 500)) {
            String[] parts = memory.content().split("=", 2);
            records.add(record(parts.length > 0 ? parts[0] : memory.id(),
                    parts.length > 1 ? parts[1] : "", Map.of("global", true)));
        }
        records.sort((a, b) -> Long.compare(timestampOf(b), timestampOf(a)));
        return paginate(records, limit, offset);
    }

    /** 删除全局记忆；key 为空时删除全部。 */
    public void deleteGlobalMemory(String tenantId, String key) {
        if (!hasText(tenantId)) {
            return;
        }
        for (L1AtomicMemory memory : oltp.recallAtomicByType(tenantId, TYPE_GLOBAL, 10_000)) {
            if (!hasText(key) || memory.content().startsWith(key + "=")) {
                oltp.deleteAtomicMemory(memory.id());
            }
        }
    }

    /* ---------------- 内部工具（保持原返回结构兼容） ---------------- */

    private boolean isCategoryAllowed(String category) {
        return whitelistCategories.isEmpty() || whitelistCategories.contains(category);
    }

    private String sanitizeIfEnabled(String value) {
        if (value == null) {
            return "";
        }
        return sensitiveFiltering ? sensitiveFilter.sanitize(value) : value;
    }

    private Map<String, Object> result(Map<String, Object> record, String raw, String safe) {
        Map<String, Object> resultMap = new LinkedHashMap<>(record);
        resultMap.put("sensitiveMasked", !java.util.Objects.equals(raw, safe));
        return resultMap;
    }

    private Map<String, Object> record(String key, String content, Map<String, Object> extra) {
        Map<String, Object> recordMap = new LinkedHashMap<>();
        recordMap.put("id", key);
        recordMap.put("content", content);
        recordMap.put("timestamp", nowText());
        recordMap.put("ts", System.currentTimeMillis());
        if (extra != null) {
            recordMap.putAll(extra);
        }
        return recordMap;
    }

    private long timestampOf(Map<String, Object> record) {
        Object ts = record.get("ts");
        return ts instanceof Number number ? number.longValue() : 0L;
    }

    private List<Map<String, Object>> paginate(
            List<Map<String, Object>> records, Integer limit, Integer offset) {
        int from = offset != null && offset > 0 ? offset : 0;
        if (from >= records.size()) {
            return List.of();
        }
        int end = limit != null && limit > 0
                ? Math.min(records.size(), from + limit)
                : records.size();
        return new ArrayList<>(records.subList(from, end));
    }

    private String nowText() {
        return LocalDateTime.ofInstant(java.time.Instant.now(), ZONE).toString().replace('T', ' ').substring(0, 19);
    }

    private void requireText(String value, String message) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
