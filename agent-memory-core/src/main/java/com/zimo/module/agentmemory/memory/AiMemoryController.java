package com.zimo.module.agentmemory.memory;

import com.zimo.module.agentmemory.security.AiMemorySensitiveFilter;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能体记忆管理接口。
 *
 * <ul>
 *   <li>会话记忆：单次会话上下文与业务变量</li>
 *   <li>用户长期记忆：员工画像、历史习惯、业务历史记录</li>
 *   <li>全局持久记忆：跨会话、跨智能体共享</li>
 *   <li>记忆策略：白名单类别与敏感过滤规则查询</li>
 * </ul>
 *
 * @author WorkBuddy
 * @since 2026-08-08
 */
@RestController
@RequestMapping("/api/ai/memory")
public class AiMemoryController {

    private final AiMemoryService memoryService;
    private final MemorySecurityConfig securityConfig;
    private final AiMemorySensitiveFilter sensitiveFilter;

    public AiMemoryController(
            AiMemoryService memoryService,
            MemorySecurityConfig securityConfig,
            AiMemorySensitiveFilter sensitiveFilter) {
        this.memoryService = memoryService;
        this.securityConfig = securityConfig;
        this.sensitiveFilter = sensitiveFilter;
    }

    /* ---------------- 短期会话记忆 ---------------- */

    @GetMapping("/session/{sessionId}")
    public List<Map<String, Object>> getSessionVars(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return memoryService.getSessionVars(tenantId, sessionId, limit, offset);
    }

    @PostMapping("/session/{sessionId}")
    public Map<String, Object> saveSessionVar(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestBody Map<String, String> body) {
        return memoryService.saveSessionVar(tenantId, sessionId, body.get("key"), body.get("value"));
    }

    @DeleteMapping("/session/{sessionId}")
    public Map<String, Object> deleteSessionVar(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestParam String key) {
        memoryService.deleteSessionVar(tenantId, sessionId, key);
        return Map.of("deleted", true);
    }

    /* ---------------- 用户长期记忆 ---------------- */

    @GetMapping("/user/{userId}")
    public List<Map<String, Object>> getUserMemory(
            @PathVariable String userId,
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return memoryService.listUserMemory(tenantId, userId, category, limit, offset);
    }

    @PostMapping("/user/{userId}")
    public Map<String, Object> saveUserMemory(
            @PathVariable String userId,
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestBody Map<String, String> body) {
        return memoryService.saveUserMemory(tenantId, userId, body.get("category"), body.get("content"));
    }

    @DeleteMapping("/user/{userId}")
    public Map<String, Object> deleteUserMemory(
            @PathVariable String userId,
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestParam(required = false) String id) {
        memoryService.deleteUserMemory(tenantId, userId, id);
        return Map.of("deleted", true);
    }

    /* ---------------- 全局持久记忆 ---------------- */

    @GetMapping("/global")
    public List<Map<String, Object>> getGlobalMemory(
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return memoryService.listGlobalMemory(tenantId, limit, offset);
    }

    @PostMapping("/global")
    public Map<String, Object> saveGlobalMemory(
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestBody Map<String, String> body) {
        return memoryService.saveGlobalMemory(tenantId, body.get("key"), body.get("content"));
    }

    @DeleteMapping("/global")
    public Map<String, Object> deleteGlobalMemory(
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestParam(required = false) String key) {
        memoryService.deleteGlobalMemory(tenantId, key);
        return Map.of("deleted", true);
    }

    /* ---------------- 记忆策略 ---------------- */

    @GetMapping("/policy")
    public Map<String, Object> policy() {
        return Map.of(
                "whitelistCategories", securityConfig.whitelistCategories(),
                "whitelistEnabled", securityConfig.whitelistEnabled(),
                "sensitiveFiltering", securityConfig.sensitiveFiltering(),
                "sensitiveRules", sensitiveFilter.ruleNames());
    }
}
