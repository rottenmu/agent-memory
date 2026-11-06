package com.zimo.agent.memory.memory;

import cn.hutool.core.util.IdUtil;
import com.zimo.agent.memory.governance.ImportanceScore;
import com.zimo.agent.memory.governance.ImportanceScorer;
import com.zimo.agent.memory.model.L1AtomicMemory;
import com.zimo.agent.memory.model.L3Persona;
import com.zimo.agent.memory.model.MemoryPage;
import com.zimo.agent.memory.model.UserMemoryRow;
import com.zimo.agent.memory.security.AiMemorySensitiveFilter;
import com.zimo.agent.memory.storage.OltpMemoryRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体记忆服务（迁移自 ai-agent-spring-boot-starter，落库改为四层金字塔）。
 *
 * <p>原「会话变量/用户长期记忆/全局记忆」映射到四层模型：</p>
 * <ul>
 *   <li>会话变量 → L1 原子记忆（{@code session_var} 类型）</li>
 *   <li>用户长期记忆 → L3 画像（persona/preference 类别）与 L1（history/custom）</li>
 *   <li>全局记忆 → L1（{@code global} 类型，其 {@code user_id} 列承载 tenantId）</li>
 * </ul>
 *
 * <p>同时写入 L0 原始日志（traceId 溯源）。安全策略：类别白名单 + 敏感脱敏。</p>
 *
 * <p><b>隔离契约</b>：三个查询维度（会话 / 用户 / 全局）都以 {@code tenantId} 为强制条件，
 * 且「用户长期记忆」显式排除 {@code global} 与 {@code session_var} —— 后两者的
 * {@code user_id} 列语义不同，混入会串数据。</p>
 *
 * <p><b>分页契约</b>：列表方法返回 {@link MemoryPage}（含 {@code total} 与 {@code truncated}），
 * 分页在 SQL 层完成，不做「先截断再分页」。</p>
 *
 * <p><b>重要性契约</b>：长期记忆写入时若调用方未指定 {@code importance}，由
 * {@link ImportanceScorer} 自动评估；写入结果始终回带 {@code importanceSource}
 * （{@code scored} / {@code explicit}），让调用方一眼看出存进去的分值是「我给的」还是「系统算的」。
 * 会话变量不参与召回打分与遗忘淘汰，重要性固定为中性值。</p>
 */
public class AiMemoryService {

    /** L1 记忆类型：会话变量。 */
    public static final String TYPE_SESSION_VAR = L1AtomicMemory.TYPE_SESSION_VAR;
    /** L1 记忆类型：全局记忆。 */
    public static final String TYPE_GLOBAL = L1AtomicMemory.TYPE_GLOBAL;
    /** 重要性来源：调用方显式指定。 */
    public static final String IMPORTANCE_SOURCE_EXPLICIT = "explicit";
    /** 重要性来源：由 {@link ImportanceScorer} 自动评估。 */
    public static final String IMPORTANCE_SOURCE_SCORED = "scored";
    /** 全局记忆在重要性评估中的类别名（与 L1 的 {@code global} 类型同名，先验权重取自该类别的配置）。 */
    private static final String CATEGORY_GLOBAL = TYPE_GLOBAL;

    private static final ZoneId ZONE = ZoneId.systemDefault();
    /** 未显式指定 {@code limit} 时的默认页大小。 */
    private static final int DEFAULT_LIMIT = 50;
    /** 页大小上限：超过按此值截断，并由 {@code truncated} 向调用方暴露。 */
    private static final int MAX_LIMIT = 1000;
    /** 内部扫描（按 key 过滤删除等）的取数上限。 */
    private static final int SCAN_LIMIT = 10_000;

    private final OltpMemoryRepository oltp;
    private final TrajectoryRecorder trajectoryRecorder;
    private final AiMemorySensitiveFilter sensitiveFilter;
    private final ImportanceScorer importanceScorer;
    private final List<String> whitelistCategories;
    private final boolean sensitiveFiltering;

    /**
     * @param oltp             四层 OLTP 记忆仓储
     * @param sensitiveFilter  敏感内容过滤器
     * @param securityConfig   记忆安全策略（白名单类别 + 敏感过滤开关）
     * @param importanceScorer 重要性评估器（未显式指定重要性时使用）
     */
    public AiMemoryService(
            OltpMemoryRepository oltp,
            AiMemorySensitiveFilter sensitiveFilter,
            MemorySecurityConfig securityConfig,
            ImportanceScorer importanceScorer) {
        this.oltp = oltp;
        this.trajectoryRecorder = oltp == null ? null : new TrajectoryRecorder(oltp);
        this.sensitiveFilter = sensitiveFilter;
        this.importanceScorer = importanceScorer;
        this.whitelistCategories = securityConfig == null
                ? List.of() : securityConfig.whitelistCategories();
        this.sensitiveFiltering = securityConfig == null
                || securityConfig.sensitiveFiltering();
    }

    /* ---------------- 短期会话变量（L1 session_var） ---------------- */

    /**
     * 保存单次会话的业务变量（L1 原子记忆 + L0 原始日志）。
     *
     * <p>会话变量不参与长期召回与遗忘淘汰，因此**不做**重要性评估，固定写中性值 ——
     * 给它打分只会让人误以为这个分值会影响什么。</p>
     */
    public Map<String, Object> saveSessionVar(String tenantId, String sessionId, String key, String value) {
        requireText(tenantId, "租户标识不能为空");
        requireText(sessionId, "会话标识不能为空");
        requireText(key, "变量名不能为空");
        String safeValue = sanitizeIfEnabled(value);
        String id = IdUtil.fastSimpleUUID().substring(0, 12);
        String traceId = "trace-" + id;
        long ts = System.currentTimeMillis();
        recordMemoryWrite(sessionId, tenantId, traceId, "SESSION_VAR:" + key + "=" + safeValue);
        oltp.saveAtomicMemory(L1AtomicMemory.create(
                id, traceId, sessionId, tenantId, TYPE_SESSION_VAR,
                key + "=" + safeValue, ts, tenantId));
        Map<String, Object> recordMap = record(key, safeValue, ts, Map.of("sessionId", sessionId));
        return result(recordMap, value, safeValue);
    }

    /** 读取会话变量（分页，最新在前）。 */
    public MemoryPage getSessionVars(String tenantId, String sessionId, Integer limit, Integer offset) {
        int size = normalizeLimit(limit);
        int start = normalizeOffset(offset);
        List<Map<String, Object>> records = new ArrayList<>();
        for (L1AtomicMemory memory : oltp.recallSessionVars(tenantId, sessionId, size, start)) {
            String[] parts = memory.content().split("=", 2);
            Map<String, Object> recordMap = record(parts.length > 0 ? parts[0] : memory.id(),
                    parts.length > 1 ? parts[1] : "", memory.ts(), Map.of("sessionId", sessionId));
            recordMap.put("importance", memory.importance());
            records.add(recordMap);
        }
        return MemoryPage.of(records, oltp.countSessionVars(tenantId, sessionId), size, start);
    }

    /**
     * 删除会话变量，返回真实删除条数。
     *
     * <p>{@code key} 为空表示清空该会话全部变量 —— 早期实现在此处直接 {@code return}，
     * 什么都没删却报成功（缺陷 D13 的会话分支）。</p>
     *
     * @return 真实删除行数
     */
    public int deleteSessionVar(String tenantId, String sessionId, String key) {
        if (!hasText(tenantId) || !hasText(sessionId)) {
            return 0;
        }
        int deleted = 0;
        for (L1AtomicMemory memory : oltp.recallSessionVars(tenantId, sessionId, SCAN_LIMIT, 0)) {
            if (matchesKey(memory.content(), key)) {
                deleted += oltp.deleteAtomicMemory(tenantId, memory.id());
            }
        }
        return deleted;
    }

    /* ---------------- 用户长期记忆（L3 画像 + L1） ---------------- */

    /**
     * 保存用户长期记忆（persona/preference → L3 画像；history/custom → L1），
     * 重要性由 {@link ImportanceScorer} 自动评估。
     *
     * @param tenantId 租户标识
     * @param userId   用户标识
     * @param category 记忆类别；为空按 {@code custom} 处理
     * @param content  记忆内容
     * @return 写入结果；被白名单或敏感策略拒绝时返回 {@code allowed=false} 与原因
     */
    public Map<String, Object> saveUserMemory(String tenantId, String userId, String category, String content) {
        return saveUserMemory(tenantId, userId, category, content, null);
    }

    /**
     * 保存用户长期记忆，可显式指定重要性。
     *
     * <p>{@code importance} 为 {@code null} 时自动评估。显式值允许到 {@code 1.0}
     * （≥ {@link ImportanceScorer#PROTECTED_THRESHOLD} 即视为「显式标记重要」，免于遗忘淘汰）。</p>
     *
     * <p><b>顺序要求</b>：重要性在**脱敏前**的原文上评估。敏感降权信号依赖原文形态，
     * 若先脱敏再打分，「138****8000」已不再匹配手机号规则，敏感降权会静默失效。</p>
     *
     * @param tenantId   租户标识
     * @param userId     用户标识
     * @param category   记忆类别；为空按 {@code custom} 处理
     * @param content    记忆内容（原文）
     * @param importance 显式重要性 0~1；{@code null} 表示自动评估
     * @return 写入结果；含 {@code importance} / {@code importanceSource} / {@code importanceSignals}
     */
    public Map<String, Object> saveUserMemory(
            String tenantId, String userId, String category, String content, Double importance) {
        requireText(tenantId, "租户标识不能为空");
        requireText(userId, "用户标识不能为空");
        String normalizedCategory = hasText(category) ? category.trim() : "custom";
        if (!isCategoryAllowed(normalizedCategory)) {
            return Map.of("allowed", false, "reason",
                    "记忆类别不在白名单内，已拒绝写入: " + normalizedCategory);
        }
        ImportanceScore assessed = importanceScorer.score(content, normalizedCategory);
        String safeValue = sanitizeIfEnabled(content);
        if (!hasText(safeValue)) {
            return Map.of("allowed", false, "reason", "记忆内容为空或全部为敏感信息，已拒绝写入");
        }
        String id = IdUtil.fastSimpleUUID().substring(0, 12);
        String traceId = "trace-" + id;
        long ts = System.currentTimeMillis();
        double value = resolveImportance(importance, assessed);
        recordMemoryWrite("user-" + userId, tenantId, traceId,
                "USER_MEMORY:" + normalizedCategory + "=" + safeValue);
        if ("persona".equals(normalizedCategory) || "preference".equals(normalizedCategory)) {
            oltp.savePersona(L3Persona.create(
                    id, userId, normalizedCategory, safeValue, 1, ts, tenantId, value));
        } else {
            oltp.saveAtomicMemory(L1AtomicMemory.create(id, traceId, "user-" + userId, userId,
                    normalizedCategory, safeValue, ts, tenantId, value));
        }
        Map<String, Object> recordMap = record(id, safeValue, ts, Map.of(
                "userId", userId, "category", normalizedCategory));
        return result(applyImportance(recordMap, importance, assessed), content, safeValue);
    }

    /**
     * 查询用户长期记忆（L3 画像 ∪ L1 原子记忆，时间降序，真分页）。
     *
     * <p>{@code category} 为空表示「该用户的全部类别」—— 早期实现把空类别当成
     * {@code memory_type = ''} 传给 SQL，导致不传类别时恒返回空（缺陷 D9）。</p>
     */
    public MemoryPage listUserMemory(
            String tenantId, String userId, String category, Integer limit, Integer offset) {
        int size = normalizeLimit(limit);
        int start = normalizeOffset(offset);
        List<Map<String, Object>> records = new ArrayList<>();
        for (UserMemoryRow row : oltp.pageUserMemory(tenantId, userId, category, size, start)) {
            records.add(toRecord(userId, row));
        }
        return MemoryPage.of(records, oltp.countUserMemory(tenantId, userId, category), size, start);
    }

    /**
     * 删除指定用户记忆，返回真实删除条数。
     *
     * <p>{@code id} 可能来自 L3 画像也可能来自 L1 原子记忆（列表接口两表合并返回），
     * 因此按 id 删除时必须两表都尝试 —— 早期只删 L1，拿到画像 id 时一行都删不掉却报成功
     * （缺陷 D12）。{@code id} 为空表示清空该用户的全部长期记忆。</p>
     *
     * @return 真实删除行数
     */
    public int deleteUserMemory(String tenantId, String userId, String id) {
        requireText(tenantId, "租户标识不能为空");
        requireText(userId, "用户标识不能为空");
        if (hasText(id)) {
            return oltp.deleteAtomicMemory(tenantId, id) + oltp.deletePersonaById(tenantId, id);
        }
        return oltp.deletePersonasByUser(tenantId, userId) + oltp.deleteAtomicByUser(tenantId, userId);
    }

    /* ---------------- 全局持久记忆（L1 global） ---------------- */

    /**
     * 保存全局持久记忆（{@code user_id} 列承载 tenantId），重要性自动评估。
     *
     * @param tenantId 租户标识
     * @param key      记忆键
     * @param content  记忆内容
     * @return 写入结果；含 {@code importance} / {@code importanceSource} / {@code importanceSignals}
     */
    public Map<String, Object> saveGlobalMemory(String tenantId, String key, String content) {
        return saveGlobalMemory(tenantId, key, content, null);
    }

    /**
     * 保存全局持久记忆，可显式指定重要性。
     *
     * @param tenantId   租户标识
     * @param key        记忆键
     * @param content    记忆内容（原文）
     * @param importance 显式重要性 0~1；{@code null} 表示自动评估
     * @return 写入结果
     */
    public Map<String, Object> saveGlobalMemory(
            String tenantId, String key, String content, Double importance) {
        requireText(tenantId, "租户标识不能为空");
        requireText(key, "记忆键不能为空");
        ImportanceScore assessed = importanceScorer.score(content, CATEGORY_GLOBAL);
        String safeValue = sanitizeIfEnabled(content);
        String id = IdUtil.fastSimpleUUID().substring(0, 12);
        long ts = System.currentTimeMillis();
        double value = resolveImportance(importance, assessed);
        oltp.saveAtomicMemory(L1AtomicMemory.create(
                id, "trace-" + id, "global", tenantId, TYPE_GLOBAL,
                key + "=" + safeValue, ts, tenantId, value));
        Map<String, Object> recordMap = record(key, safeValue, ts, Map.of("global", true));
        return result(applyImportance(recordMap, importance, assessed), content, safeValue);
    }

    /** 查询全局持久记忆列表（分页，最新在前）。 */
    public MemoryPage listGlobalMemory(String tenantId, Integer limit, Integer offset) {
        int size = normalizeLimit(limit);
        int start = normalizeOffset(offset);
        List<Map<String, Object>> records = new ArrayList<>();
        for (L1AtomicMemory memory
                : oltp.recallAtomicByType(tenantId, tenantId, TYPE_GLOBAL, size, start)) {
            String[] parts = memory.content().split("=", 2);
            Map<String, Object> recordMap = record(parts.length > 0 ? parts[0] : memory.id(),
                    parts.length > 1 ? parts[1] : "", memory.ts(), Map.of("global", true));
            recordMap.put("importance", memory.importance());
            records.add(recordMap);
        }
        return MemoryPage.of(records, oltp.countAtomicByType(tenantId, tenantId, TYPE_GLOBAL),
                size, start);
    }

    /**
     * 删除全局记忆，返回真实删除条数。
     *
     * @param key 记忆键；为空表示清空该租户全部全局记忆
     * @return 真实删除行数
     */
    public int deleteGlobalMemory(String tenantId, String key) {
        if (!hasText(tenantId)) {
            return 0;
        }
        if (!hasText(key)) {
            return oltp.deleteAtomicByType(tenantId, tenantId, TYPE_GLOBAL);
        }
        int deleted = 0;
        for (L1AtomicMemory memory
                : oltp.recallAtomicByType(tenantId, tenantId, TYPE_GLOBAL, SCAN_LIMIT, 0)) {
            if (matchesKey(memory.content(), key)) {
                deleted += oltp.deleteAtomicMemory(tenantId, memory.id());
            }
        }
        return deleted;
    }

    /* ---------------- 内部工具（保持原返回结构兼容） ---------------- */

    /** 把 UNION 行视图转成对外记录（字段名保持既有约定，另补 layer / importance 等新字段）。 */
    private Map<String, Object> toRecord(String userId, UserMemoryRow row) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("userId", userId);
        extra.put("category", row.category());
        if (row.version() != null) {
            extra.put("version", row.version());
        }
        extra.put("importance", row.importance());
        extra.put("accessCount", row.accessCount());
        if (row.lastAccessAt() != null) {
            extra.put("lastAccessAt", row.lastAccessAt());
        }
        extra.put("layer", row.layer());
        return record(row.memoryId(), row.content(), row.ts(), extra);
    }

    /**
     * 决定最终写入的重要性：显式值优先，未指定时取自动评估结果。
     *
     * @param explicit 调用方显式指定的重要性；{@code null} 表示未指定
     * @param assessed 自动评估结果
     * @return 最终重要性
     */
    private double resolveImportance(Double explicit, ImportanceScore assessed) {
        return explicit == null ? assessed.value() : ImportanceScorer.clampExplicit(explicit);
    }

    /**
     * 把重要性与其来源写进返回体。
     *
     * <p>显式指定时不再回显自动信号：那份信号并未参与本次打分，回显只会让调用方
     * 误以为存进去的分值是「系统算出来的」，掩盖「我的入参生效了没有」这个关键问题。</p>
     *
     * @param recordMap 写入记录
     * @param explicit  调用方显式指定的重要性；{@code null} 表示未指定
     * @param assessed  自动评估结果
     * @return 补充了重要性字段的写入记录
     */
    private Map<String, Object> applyImportance(
            Map<String, Object> recordMap, Double explicit, ImportanceScore assessed) {
        boolean explicitGiven = explicit != null;
        recordMap.put("importance", resolveImportance(explicit, assessed));
        recordMap.put("importanceSource",
                explicitGiven ? IMPORTANCE_SOURCE_EXPLICIT : IMPORTANCE_SOURCE_SCORED);
        recordMap.put("importanceSignals", explicitGiven ? List.of() : assessed.signalNames());
        return recordMap;
    }

    private boolean isCategoryAllowed(String category) {
        return whitelistCategories.isEmpty() || whitelistCategories.contains(category);
    }

    private String sanitizeIfEnabled(String value) {
        if (value == null) {
            return "";
        }
        return sensitiveFiltering ? sensitiveFilter.sanitize(value) : value;
    }

    private Map<String, Object> result(Map<String, Object> recordMap, String raw, String safe) {
        Map<String, Object> resultMap = new LinkedHashMap<>(recordMap);
        resultMap.put("sensitiveMasked", !java.util.Objects.equals(raw, safe));
        return resultMap;
    }

    /**
     * 组装一条对外记录。
     *
     * <p>{@code ts} 与 {@code timestamp} 一律取**记忆自身的发生时间**。早期实现写入的是
     * 查询时刻，导致「按时间降序」实际排的是遍历顺序，分页结果在翻页时还会漂移（缺陷 D11）。</p>
     */
    private Map<String, Object> record(String key, String content, long ts, Map<String, Object> extra) {
        Map<String, Object> recordMap = new LinkedHashMap<>();
        recordMap.put("id", key);
        recordMap.put("content", content);
        recordMap.put("timestamp", textOf(ts));
        recordMap.put("ts", ts);
        if (extra != null) {
            recordMap.putAll(extra);
        }
        return recordMap;
    }

    /** 内容形如 {@code key=value}；{@code key} 为空表示不限定。 */
    private boolean matchesKey(String content, String key) {
        return !hasText(key) || (content != null && content.startsWith(key + "="));
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private int normalizeOffset(Integer offset) {
        return offset == null || offset < 0 ? 0 : offset;
    }

    private String textOf(long ts) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZONE)
                .toString().replace('T', ' ').substring(0, 19);
    }

    /** 记忆内容先经 {@link #sanitizeIfEnabled(String)} 再交给轨迹记录器。 */
    private void recordMemoryWrite(String sessionId, String userId, String traceId, String content) {
        if (trajectoryRecorder != null) {
            trajectoryRecorder.record(sessionId, userId, traceId, "system",
                    TrajectoryRecorder.SOURCE_MEMORY_WRITE, content, null);
        }
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
