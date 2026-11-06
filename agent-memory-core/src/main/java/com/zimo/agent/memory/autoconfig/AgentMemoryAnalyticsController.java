package com.zimo.agent.memory.autoconfig;

import com.zimo.agent.memory.analytics.MemoryAnalyticsService;
import com.zimo.agent.memory.memoryfile.MemoryFileService;
import com.zimo.agent.memory.memoryfile.MemoryFileStore;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * agent-memory 模块的 OLAP 分析接口（只读，基于 Arrow/Calcite，统一返回 JSON）。
 *
 * <p>路径前缀为 {@code /api/agent-memory/analytics}，鉴权遵循应用统一拦截配置；
 * 本类只做离线分析报表查询，运行时记忆 CRUD 走 {@code /api/ai/memory}（OLTP）。</p>
 */
@RestController
@RequestMapping("/api/agent-memory/analytics")
public class AgentMemoryAnalyticsController {

    private final MemoryAnalyticsService analyticsService;
    private final MemoryFileService fileService;

    public AgentMemoryAnalyticsController(
            MemoryAnalyticsService analyticsService, MemoryFileService fileService) {
        this.analyticsService = analyticsService;
        this.fileService = fileService;
    }

    /** 会话聚合统计。 */
    @GetMapping("/session-stats")
    public List<Map<String, Object>> sessionStats() {
        return analyticsService.sessionStats();
    }

    /** 用户行为时序（近 N 天消息量）。 */
    @GetMapping("/user-activity")
    public List<Map<String, Object>> userActivity(
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "7") int days) {
        return analyticsService.userDailyActivity(userId, Math.max(1, Math.min(days, 90)));
    }

    /** 记忆蒸馏质量评估（L0 事件构成）。 */
    @GetMapping("/distillation-stats")
    public List<Map<String, Object>> distillationStats() {
        return analyticsService.memoryDistillationStats();
    }

    /** 按 traceId 查询完整事件链（溯源）。 */
    @GetMapping("/trace")
    public List<Map<String, Object>> trace(@RequestParam String traceId) {
        return analyticsService.traceEvents(traceId);
    }

    /** 自定义 OLAP SQL（Calcite 查询内存宽表 olap_l0_log）。 */
    @GetMapping("/query")
    public List<Map<String, Object>> query(@RequestParam String sql) {
        return analyticsService.query(sql);
    }

    /**
     * GET /messages：分页查询会话消息，OLAP 宽表按会话过滤；每条记录附带 Trajectory 来源。
     *
     * @param sessionId 会话 ID（可选；仅允许字母/数字/下划线/连字符，防 SQL 注入）
     * @param page      页码（从 0 开始）
     * @param size      每页条数（默认 50，上限 500）
     * @return 分页数据；每条消息包含 trace_id、session_id、role、content、ts 和 source
     */
    @GetMapping("/messages")
    public Map<String, Object> messages(
            @RequestParam(required = false) String sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int safeSize = Math.max(1, Math.min(size, 500));
        int offset = Math.max(0, page) * safeSize;
        String where = "";
        Object[] args = null;
        if (sessionId != null && !sessionId.isBlank()) {
            where = " WHERE session_id = '" + sessionId.replaceAll("[^a-zA-Z0-9_-]", "") + "'";
        }
        List<Map<String, Object>> rows = analyticsService.query(
                "SELECT trace_id, session_id, role, content, ts, source FROM olap_l0_log"
                        + where + " ORDER BY ts DESC LIMIT " + safeSize + " OFFSET " + offset);
        List<Map<String, Object>> all = analyticsService.query(
                "SELECT COUNT(*) AS cnt FROM olap_l0_log" + where);
        long total = all.isEmpty() ? 0L : Long.parseLong(String.valueOf(all.get(0).get("cnt")));
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("rows", rows);
        m.put("total", total);
        m.put("page", page);
        m.put("size", safeSize);
        return m;
    }

    /**
     * GET /session-messages：返回会话 L0 消息列表和来源记忆文件信息，每条消息附带 Trajectory 来源。
     *
     * @param sessionId 会话 ID（仅允许字母/数字/下划线/连字符，防 SQL 注入）
     * @param agentId   智能体标识（默认 default，定位文件兼容模式记忆文件 {baseDir}/{agentId}/MEMORY.md）
     * @return 会话消息、来源记忆文件信息；每条消息包含 trace_id、session_id、role、content、ts 和 source
     */
    @GetMapping("/session-messages")
    public Map<String, Object> sessionMessages(
            @RequestParam String sessionId,
            @RequestParam(defaultValue = "default") String agentId) {
        String safe = sessionId.replaceAll("[^a-zA-Z0-9_-]", "");
        List<Map<String, Object>> messages = analyticsService.query(
                "SELECT trace_id, session_id, role, content, ts, source FROM olap_l0_log "
                        + "WHERE session_id = '" + safe + "' ORDER BY ts ASC");
        MemoryFileStore store = fileService.storeFor(agentId);
        String fileDesc = store.location();
        return Map.of(
                "agentId", agentId,
                "sessionId", safe,
                "memoryFileName", "MEMORY.md",
                "memoryFilePath", fileDesc,
                "messages", messages);
    }
}
